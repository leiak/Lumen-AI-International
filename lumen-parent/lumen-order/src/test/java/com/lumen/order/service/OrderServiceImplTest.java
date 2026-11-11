package com.lumen.order.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lumen.order.OrderConstants;
import com.lumen.order.dto.CargoRequest;
import com.lumen.order.dto.CreateOrderRequest;
import com.lumen.order.dto.OrderDetailView;
import com.lumen.order.dto.ServiceItemRequest;
import com.lumen.order.entity.Order;
import com.lumen.order.entity.OrderCargo;
import com.lumen.order.entity.OrderServiceItem;
import com.lumen.order.error.OrderErrorCode;
import com.lumen.order.exception.OrderException;
import com.lumen.order.mapper.OrderCargoMapper;
import com.lumen.order.mapper.OrderMapper;
import com.lumen.order.mapper.OrderServiceItemMapper;
import com.lumen.order.service.impl.OrderServiceImpl;
import com.lumen.numbering.service.NumberGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OrderServiceImpl 单元测试 (Iteration 4.6).
 *
 * <p>纯 Mockito, 不带 {@code @SpringBootTest} —— 与 {@code lumen-dispatch} / {@code lumen-customer}
 * 的既有做法一致 (它们一共 26 个测试文件全部无 {@code @SpringBootTest})。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OrderServiceImpl 单元测试")
class OrderServiceImplTest {

    @Mock private OrderMapper orderMapper;
    @Mock private OrderCargoMapper cargoMapper;
    @Mock private OrderServiceItemMapper serviceItemMapper;
    @Mock private NumberGenerator numberGenerator;

    private OrderService service;

    @BeforeEach
    void setUp() {
        service = new OrderServiceImpl(orderMapper, cargoMapper, serviceItemMapper, numberGenerator);
        lenient().when(numberGenerator.generate(OrderConstants.NUMBER_RULE_CODE))
                .thenReturn("ORD-20261110-000001");
        // Mockito 对 int 返回值的默认是 0 —— 而 0 行在业务上意味着「乐观锁冲突」,
        // 所以默认必须打 1, 否则每个走到 updateById 的用例都会撞 ORDER_CONFLICT。
        lenient().when(orderMapper.updateById(any(Order.class))).thenReturn(1);
    }

    private CreateOrderRequest sampleRequest() {
        return requestWith(42L, "CNY", false);
    }

    /**
     * 造一个建单请求 —— {@code settlementCurrency} / {@code isOversized} 可传 null
     * (模拟「客户端没带这两个字段」的部分更新请求)。
     */
    private CreateOrderRequest requestWith(Long customerId, String settlementCurrency, Boolean isOversized) {
        return requestWith(customerId, settlementCurrency, isOversized, defaultCargo(), defaultServiceItems());
    }

    private CreateOrderRequest requestWith(Long customerId, String settlementCurrency, Boolean isOversized,
                                           List<CargoRequest> cargo, List<ServiceItemRequest> serviceItems) {
        return new CreateOrderRequest(
                customerId, "OCEAN_FCL", "EXPORT", "FOB", "D2D",
                "上海洋山港", "洛杉矶港",
                null, null, null,
                1, "40HQ",
                "MSKU1234567", "五金配件",
                isOversized, null,
                settlementCurrency, "测试委托单",
                cargo, serviceItems);
    }

    private List<CargoRequest> defaultCargo() {
        return List.of(new CargoRequest("钢材", "STEEL", "7208", "CARTON", 10d, "CTN",
                1000d, 900d, 12.5d, 100d, 80d, 50d, true,
                5000d, "CNY", "ACME", "A1", false, null, null, null));
    }

    private List<ServiceItemRequest> defaultServiceItems() {
        return List.of(new ServiceItemRequest("OCEAN_FREIGHT", "海运费", "FREIGHT", "PER_CONTAINER",
                1d, 3000d, 3000d, "CNY", null, 2500d, false, null));
    }

    /** 库里已存在的委托单 —— DRAFT 态 (可编辑), 币种/超限标记由参数决定。 */
    private Order editableOrder(String settlementCurrency, int isOversized) {
        Order o = new Order();
        o.setId(100L);
        o.setOrderNo("ORD-20261110-000001");
        o.setOrderStatus(OrderConstants.STATUS_DRAFT);
        o.setCustomerId(42L);
        o.setSettlementCurrency(settlementCurrency);
        o.setIsOversized(isOversized);
        return o;
    }

    // ------------------------------------------------------------ 建单

    @Test
    @DisplayName("create: 生成委托单号 + 落草稿态 + 明细聚合回写主表")
    void create_generatesNoAndAggregates() {
        // insert 那一刻的快照 —— 必须当场取值: 同一个 Order 实例随后会被 replaceChildren
        // 改写聚合值, 事后用 captor 抓到的会是「被改过」的实例 (原来的假绿就出在这儿)。
        AtomicReference<BigDecimal> totalQtyAtInsert = new AtomicReference<>();
        when(orderMapper.insert(any(Order.class))).thenAnswer(inv -> {
            Order o = inv.getArgument(0);
            o.setId(100L);
            // 模拟 MyMetaObjectHandler.insertFill 填的 version=0 (单测 mock 掉了真实插入)
            o.setVersion(0);
            totalQtyAtInsert.set(o.getTotalQty());
            return 1;
        });
        when(cargoMapper.insert(any(OrderCargo.class))).thenReturn(1);
        when(serviceItemMapper.insert(any(OrderServiceItem.class))).thenReturn(1);

        var view = service.create(sampleRequest());

        assertThat(view.orderNo()).isEqualTo("ORD-20261110-000001");
        assertThat(view.orderStatus()).isEqualTo(OrderConstants.STATUS_DRAFT);
        assertThat(view.customerId()).isEqualTo(42L);

        // insert 时点上聚合值还没算出来 —— 聚合是 replaceChildren 之后才回写的
        assertThat(totalQtyAtInsert.get()).isNull();

        // 聚合回写确实发生了: 那条 UPDATE 带着算好的聚合值 (删掉它, 这条断言立刻红)
        verify(orderMapper).updateById(argThat((Order o) ->
                o.getTotalQty() != null
                        && o.getTotalQty().compareTo(new BigDecimal("10.000")) == 0
                        && o.getTotalWeightKg() != null
                        && o.getTotalWeightKg().compareTo(new BigDecimal("1000.000")) == 0
                        && o.getTotalVolumeCbm() != null
                        && o.getTotalVolumeCbm().compareTo(new BigDecimal("12.500")) == 0
                        && o.getIsDangerous() != null && o.getIsDangerous() == 0));
    }

    @Test
    @DisplayName("create: 聚合回写那条 UPDATE 带 version (受乐观锁保护)")
    void create_aggregateUpdateCarriesVersion() {
        when(orderMapper.insert(any(Order.class))).thenAnswer(inv -> {
            Order o = inv.getArgument(0);
            o.setId(100L);
            o.setVersion(0);   // insertFill 填的值
            return 1;
        });
        when(cargoMapper.insert(any(OrderCargo.class))).thenReturn(1);
        when(serviceItemMapper.insert(any(OrderServiceItem.class))).thenReturn(1);

        service.create(sampleRequest());

        verify(orderMapper).updateById(argThat((Order o) -> o.getVersion() != null && o.getVersion() == 0));
    }

    @Test
    @DisplayName("create: 货物明细为空 → OrderException(CARGO_EMPTY)")
    void create_emptyCargo_throws() {
        CreateOrderRequest req = requestWith(42L, "CNY", false, List.of(), defaultServiceItems());

        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(OrderException.class)
                .extracting(e -> ((OrderException) e).getErrorCode())
                .isEqualTo(OrderErrorCode.CARGO_EMPTY);
        verify(orderMapper, never()).insert(any(Order.class));
    }

    @Test
    @DisplayName("create: 服务项为空 → OrderException(SERVICE_ITEM_EMPTY)")
    void create_emptyServiceItems_throws() {
        CreateOrderRequest req = requestWith(42L, "CNY", false, defaultCargo(), List.of());

        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(OrderException.class)
                .extracting(e -> ((OrderException) e).getErrorCode())
                .isEqualTo(OrderErrorCode.SERVICE_ITEM_EMPTY);
    }

    // ------------------------------------------------------------ 受控词表 (枚举校验)

    @Test
    @DisplayName("create: businessType 不在受控词表内 → ENUM_VALUE_INVALID, 且一行都不写库")
    void create_invalidBusinessType_throws() {
        CreateOrderRequest req = new CreateOrderRequest(
                42L, "XXX", "EXPORT", "FOB", "D2D", null, null, null, null, null,
                null, null, null, null, false, null, "CNY", null,
                defaultCargo(), defaultServiceItems());

        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(OrderException.class)
                .extracting(e -> ((OrderException) e).getErrorCode())
                .isEqualTo(OrderErrorCode.ENUM_VALUE_INVALID);
        verify(orderMapper, never()).insert(any(Order.class));
    }

    @Test
    @DisplayName("create: 服务项 calcBasis 非法 → ENUM_VALUE_INVALID")
    void create_invalidCalcBasis_throws() {
        List<ServiceItemRequest> items = List.of(
                new ServiceItemRequest("OCEAN_FREIGHT", "海运费", "FREIGHT", "XXX",
                        1d, 3000d, 3000d, "CNY", null, 2500d, false, null));
        CreateOrderRequest req = requestWith(42L, "CNY", false, defaultCargo(), items);

        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(OrderException.class)
                .extracting(e -> ((OrderException) e).getErrorCode())
                .isEqualTo(OrderErrorCode.ENUM_VALUE_INVALID);
        verify(orderMapper, never()).insert(any(Order.class));
    }

    @Test
    @DisplayName("create: feeCategory 为 null (可空列) → 放行")
    void create_nullFeeCategory_passes() {
        List<ServiceItemRequest> items = List.of(
                new ServiceItemRequest("OCEAN_FREIGHT", "海运费", null, "PER_CONTAINER",
                        1d, 3000d, 3000d, "CNY", null, 2500d, false, null));
        CreateOrderRequest req = requestWith(42L, "CNY", false, defaultCargo(), items);

        when(orderMapper.insert(any(Order.class))).thenAnswer(inv -> {
            Order o = inv.getArgument(0);
            o.setId(100L);
            o.setVersion(0);
            return 1;
        });
        when(cargoMapper.insert(any(OrderCargo.class))).thenReturn(1);
        when(serviceItemMapper.insert(any(OrderServiceItem.class))).thenReturn(1);

        assertThat(service.create(req).orderNo()).isEqualTo("ORD-20261110-000001");
    }

    // ------------------------------------------------------------ 数值越界

    @Test
    @DisplayName("create: pkgQty=1e999 (Jackson 解析成 Infinity) → NUMERIC_OUT_OF_RANGE, 不是 500")
    void create_infinitePkgQty_throwsNumericOutOfRange() {
        List<CargoRequest> cargo = List.of(new CargoRequest("钢材", null, null, null, Double.POSITIVE_INFINITY,
                null, null, null, null, null, null, null, true, null, null, null, null, false, null, null, null));
        CreateOrderRequest req = requestWith(42L, "CNY", false, cargo, defaultServiceItems());

        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(OrderException.class)
                .extracting(e -> ((OrderException) e).getErrorCode())
                .isEqualTo(OrderErrorCode.NUMERIC_OUT_OF_RANGE);
        // 数值预检在写库之前 —— 失败请求一行都不该碰数据库
        verify(orderMapper, never()).insert(any(Order.class));
    }

    @Test
    @DisplayName("create: pkgQty=1e15 (超出 DECIMAL(18,3)) → NUMERIC_OUT_OF_RANGE, 不是 MySQL 1264")
    void create_overflowPkgQty_throwsNumericOutOfRange() {
        List<CargoRequest> cargo = List.of(new CargoRequest("钢材", null, null, null, 1e15d,
                null, null, null, null, null, null, null, true, null, null, null, null, false, null, null, null));
        CreateOrderRequest req = requestWith(42L, "CNY", false, cargo, defaultServiceItems());

        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(OrderException.class)
                .extracting(e -> ((OrderException) e).getErrorCode())
                .isEqualTo(OrderErrorCode.NUMERIC_OUT_OF_RANGE);
        verify(orderMapper, never()).insert(any(Order.class));
    }

    @Test
    @DisplayName("create: pkgQty=999999999999999.9 (DECIMAL(18,3) 边界内) → 正常落库")
    void create_boundaryPkgQty_passes() {
        // 注意: 理论边界 999999999999999.999 **写不进 double** —— DTO 的数值字段是 Double,
        // 最近的可表示 double 是 1.0E15 (已经越界), 所以这里用次大可表示值 999999999999999.9。
        List<CargoRequest> cargo = List.of(new CargoRequest("钢材", null, null, null, 999999999999999.9d,
                null, null, null, null, null, null, null, true, null, null, null, null, false, null, null, null));
        CreateOrderRequest req = requestWith(42L, "CNY", false, cargo, defaultServiceItems());

        when(orderMapper.insert(any(Order.class))).thenAnswer(inv -> {
            Order o = inv.getArgument(0);
            o.setId(100L);
            o.setVersion(0);
            return 1;
        });
        when(cargoMapper.insert(any(OrderCargo.class))).thenReturn(1);
        when(serviceItemMapper.insert(any(OrderServiceItem.class))).thenReturn(1);

        assertThat(service.create(req).orderNo()).isEqualTo("ORD-20261110-000001");
    }

    // ------------------------------------------------------------ 查询

    @Test
    @DisplayName("get: 委托单不存在 → OrderException(ORDER_NOT_FOUND)")
    void get_missing_throws() {
        when(orderMapper.selectById(999L)).thenReturn(null);

        assertThatThrownBy(() -> service.get(999L))
                .isInstanceOf(OrderException.class)
                .extracting(e -> ((OrderException) e).getErrorCode())
                .isEqualTo(OrderErrorCode.ORDER_NOT_FOUND);
    }

    @Test
    @DisplayName("get: 返回详情 + 明细 + 服务项 (allowedNextStates 本轮恒为空列表)")
    void get_returnsDetailWithChildren() {
        Order o = new Order();
        o.setId(100L);
        o.setOrderNo("ORD-20261110-000001");
        o.setOrderStatus(OrderConstants.STATUS_DRAFT);
        o.setCustomerId(42L);
        when(orderMapper.selectById(100L)).thenReturn(o);

        OrderCargo c = new OrderCargo();
        c.setId(1L); c.setOrderId(100L); c.setCargoNameCn("钢材"); c.setPkgQty(BigDecimal.TEN);
        when(cargoMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(c));

        OrderServiceItem s = new OrderServiceItem();
        s.setId(2L); s.setOrderId(100L); s.setFeeName("海运费"); s.setAmount(BigDecimal.valueOf(3000));
        when(serviceItemMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(s));

        OrderDetailView view = service.get(100L);

        assertThat(view.orderNo()).isEqualTo("ORD-20261110-000001");
        assertThat(view.cargo()).hasSize(1);
        assertThat(view.serviceItems()).hasSize(1);
    }

    @Test
    @DisplayName("getOwned: customerId 为 null → CUSTOMER_NOT_FOUND, 不是 NPE 500")
    void getOwned_nullCustomerId_throws() {
        assertThatThrownBy(() -> service.getOwned(100L, null))
                .isInstanceOf(OrderException.class)
                .extracting(e -> ((OrderException) e).getErrorCode())
                .isEqualTo(OrderErrorCode.CUSTOMER_NOT_FOUND);
    }

    // ------------------------------------------------------------ 编辑路径

    @Test
    @DisplayName("update: 请求不带 settlementCurrency / isOversized → 保留库里的原值")
    void update_omittingCurrencyAndOversizedKeepsDbValues() {
        Order o = editableOrder("USD", 1);
        when(orderMapper.selectById(100L)).thenReturn(o);
        when(cargoMapper.insert(any(OrderCargo.class))).thenReturn(1);
        when(serviceItemMapper.insert(any(OrderServiceItem.class))).thenReturn(1);

        service.update(100L, requestWith(42L, null, null));

        // 两次 updateById: 头部更新 + 明细聚合回写
        ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
        verify(orderMapper, times(2)).updateById(captor.capture());
        Order headerUpdate = captor.getAllValues().get(0);
        assertThat(headerUpdate.getSettlementCurrency()).isEqualTo("USD");
        assertThat(headerUpdate.getIsOversized()).isEqualTo(1);

        // 聚合回写那条 UPDATE 只带 4 个聚合列 —— 两列是 null, 不进 SET, 不会把原值冲掉
        Order aggUpdate = captor.getAllValues().get(1);
        assertThat(aggUpdate.getSettlementCurrency()).isNull();
        assertThat(aggUpdate.getIsOversized()).isNull();
    }

    @Test
    @DisplayName("update: 请求带 isOversized=false → 落 0 (取消超限标记)")
    void update_withOversizedFalseClearsFlag() {
        Order o = editableOrder("USD", 1);
        when(orderMapper.selectById(100L)).thenReturn(o);
        when(cargoMapper.insert(any(OrderCargo.class))).thenReturn(1);
        when(serviceItemMapper.insert(any(OrderServiceItem.class))).thenReturn(1);

        service.update(100L, requestWith(42L, null, false));

        ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
        verify(orderMapper, times(2)).updateById(captor.capture());
        Order headerUpdate = captor.getAllValues().get(0);
        assertThat(headerUpdate.getIsOversized()).isZero();
        // 没带币种 → 币种不动
        assertThat(headerUpdate.getSettlementCurrency()).isEqualTo("USD");
    }

    @Test
    @DisplayName("update: 请求带 settlementCurrency=USD → 落 USD")
    void update_withCurrencyOverwritesDbValue() {
        Order o = editableOrder("CNY", 0);
        when(orderMapper.selectById(100L)).thenReturn(o);
        when(cargoMapper.insert(any(OrderCargo.class))).thenReturn(1);
        when(serviceItemMapper.insert(any(OrderServiceItem.class))).thenReturn(1);

        service.update(100L, requestWith(42L, "USD", null));

        ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
        verify(orderMapper, times(2)).updateById(captor.capture());
        Order headerUpdate = captor.getAllValues().get(0);
        assertThat(headerUpdate.getSettlementCurrency()).isEqualTo("USD");
        // 没带超限标记 → 标记不动
        assertThat(headerUpdate.getIsOversized()).isZero();
    }

    @Test
    @DisplayName("update: 请求带 customerId=999 → 归属不变 (不能把单子转到别的客户名下)")
    void update_withCustomerIdInBody_keepsOriginalOwner() {
        Order o = editableOrder("CNY", 0);
        when(orderMapper.selectById(100L)).thenReturn(o);
        when(cargoMapper.insert(any(OrderCargo.class))).thenReturn(1);
        when(serviceItemMapper.insert(any(OrderServiceItem.class))).thenReturn(1);

        service.update(100L, requestWith(999L, null, null));

        ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
        verify(orderMapper, times(2)).updateById(captor.capture());
        assertThat(captor.getAllValues().get(0).getCustomerId()).isEqualTo(42L);
    }

    @Test
    @DisplayName("update: 头部 UPDATE 命中 0 行 (期间被别人改过) → ORDER_CONFLICT, 且明细一行都不动")
    void update_headerUpdateHitsZeroRows_throwsConflictAndKeepsChildren() {
        Order o = editableOrder("CNY", 0);
        when(orderMapper.selectById(100L)).thenReturn(o);
        when(orderMapper.updateById(any(Order.class))).thenReturn(0);

        assertThatThrownBy(() -> service.update(100L, requestWith(42L, null, null)))
                .isInstanceOf(OrderException.class)
                .extracting(e -> ((OrderException) e).getErrorCode())
                .isEqualTo(OrderErrorCode.ORDER_CONFLICT);

        // 冲突判定必须在明细软删/重插之前 —— 一行明细都不许动
        verify(cargoMapper, never()).delete(any(LambdaQueryWrapper.class));
        verify(serviceItemMapper, never()).delete(any(LambdaQueryWrapper.class));
        verify(cargoMapper, never()).insert(any(OrderCargo.class));
        verify(serviceItemMapper, never()).insert(any(OrderServiceItem.class));
    }

    @Test
    @DisplayName("update: 状态不在可编辑集合 → ORDER_NOT_EDITABLE, 且不碰明细")
    void update_notEditableState_throws() {
        Order o = editableOrder("CNY", 0);
        o.setOrderStatus(OrderConstants.STATUS_CONFIRMED);
        when(orderMapper.selectById(100L)).thenReturn(o);

        assertThatThrownBy(() -> service.update(100L, requestWith(42L, null, null)))
                .isInstanceOf(OrderException.class)
                .extracting(e -> ((OrderException) e).getErrorCode())
                .isEqualTo(OrderErrorCode.ORDER_NOT_EDITABLE);
        verify(cargoMapper, never()).delete(any(LambdaQueryWrapper.class));
    }
}
