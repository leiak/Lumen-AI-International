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
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
@MockitoSettings(strictness = Strictness.LENIENT)
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
    }

    private CreateOrderRequest sampleRequest() {
        return requestWith("CNY", false);
    }

    /**
     * 造一个建单请求 —— {@code settlementCurrency} / {@code isOversized} 可传 null
     * (模拟「客户端没带这两个字段」的部分更新请求)。
     */
    private CreateOrderRequest requestWith(String settlementCurrency, Boolean isOversized) {
        return new CreateOrderRequest(
                42L, "OCEAN_FCL", "EXPORT", "FOB", "D2D",
                "上海洋山港", "洛杉矶港",
                null, null, null,
                1, "40HQ",
                "MSKU1234567", "五金配件",
                isOversized, null,
                settlementCurrency, "测试委托单",
                List.of(new CargoRequest("钢材", "STEEL", "7208", "CARTON", 10d, "CTN",
                        1000d, 900d, 12.5d, 100d, 80d, 50d, true,
                        5000d, "CNY", "ACME", "A1", false, null, null, null)),
                List.of(new ServiceItemRequest("OCEAN_FREIGHT", "海运费", "FREIGHT", "PER_CONTAINER",
                        1d, 3000d, 3000d, "CNY", null, 2500d, false, null)));
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

    @Test
    @DisplayName("create: 生成委托单号 + 落草稿态 + 明细聚合回写主表")
    void create_generatesNoAndAggregates() {
        Order saved = new Order();
        saved.setId(100L);
        saved.setOrderStatus(OrderConstants.STATUS_DRAFT);
        when(orderMapper.insert(any(Order.class))).thenAnswer(inv -> {
            Order o = inv.getArgument(0);
            o.setId(100L);
            return 1;
        });
        when(cargoMapper.insert(any(OrderCargo.class))).thenReturn(1);
        when(serviceItemMapper.insert(any(OrderServiceItem.class))).thenReturn(1);

        var view = service.create(sampleRequest());

        assertThat(view.orderNo()).isEqualTo("ORD-20261110-000001");
        assertThat(view.orderStatus()).isEqualTo(OrderConstants.STATUS_DRAFT);
        assertThat(view.customerId()).isEqualTo(42L);

        // 汇总回写: pkg_qty=10, weight_kg=1000, volume_cbm=12.5
        ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
        verify(orderMapper).insert(captor.capture());
        Order inserted = captor.getValue();
        assertThat(inserted.getTotalQty()).isEqualByComparingTo("10");
        assertThat(inserted.getTotalWeightKg()).isEqualByComparingTo("1000");
        assertThat(inserted.getTotalVolumeCbm()).isEqualByComparingTo("12.5");
        assertThat(inserted.getIsDangerous()).isZero();
    }

    @Test
    @DisplayName("create: 货物明细为空 → OrderException(CARGO_EMPTY)")
    void create_emptyCargo_throws() {
        CreateOrderRequest req = new CreateOrderRequest(
                42L, "OCEAN_FCL", "EXPORT", "FOB", "D2D", null, null, null, null, null,
                null, null, null, null, false, null, "CNY", null,
                List.of(), List.of(new ServiceItemRequest("OCEAN_FREIGHT", "海运费", "FREIGHT",
                        "PER_CONTAINER", 1d, 3000d, 3000d, "CNY", null, 2500d, false, null)));

        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(OrderException.class)
                .extracting(e -> ((OrderException) e).getErrorCode())
                .isEqualTo(OrderErrorCode.CARGO_EMPTY);
        verify(orderMapper, never()).insert(any(Order.class));
    }

    @Test
    @DisplayName("create: 服务项为空 → OrderException(SERVICE_ITEM_EMPTY)")
    void create_emptyServiceItems_throws() {
        CreateOrderRequest req = new CreateOrderRequest(
                42L, "OCEAN_FCL", "EXPORT", "FOB", "D2D", null, null, null, null, null,
                null, null, null, null, false, null, "CNY", null,
                List.of(new CargoRequest("钢材", null, null, null, 1d, null, 1d, null, 1d,
                        null, null, null, true, null, null, null, null, false, null, null, null)),
                List.of());

        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(OrderException.class)
                .extracting(e -> ((OrderException) e).getErrorCode())
                .isEqualTo(OrderErrorCode.SERVICE_ITEM_EMPTY);
    }

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

    // ------------------------------------------------------------ 编辑路径

    @Test
    @DisplayName("update: 请求不带 settlementCurrency / isOversized → 保留库里的原值")
    void update_omittingCurrencyAndOversizedKeepsDbValues() {
        Order o = editableOrder("USD", 1);
        when(orderMapper.selectById(100L)).thenReturn(o);
        when(cargoMapper.insert(any(OrderCargo.class))).thenReturn(1);
        when(serviceItemMapper.insert(any(OrderServiceItem.class))).thenReturn(1);

        service.update(100L, requestWith(null, null));

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

        service.update(100L, requestWith(null, false));

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

        service.update(100L, requestWith("USD", null));

        ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
        verify(orderMapper, times(2)).updateById(captor.capture());
        Order headerUpdate = captor.getAllValues().get(0);
        assertThat(headerUpdate.getSettlementCurrency()).isEqualTo("USD");
        // 没带超限标记 → 标记不动
        assertThat(headerUpdate.getIsOversized()).isZero();
    }
}
