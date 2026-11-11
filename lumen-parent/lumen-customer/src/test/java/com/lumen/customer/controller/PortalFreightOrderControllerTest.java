package com.lumen.customer.controller;

import com.lumen.common.api.PageResult;
import com.lumen.customer.dto.PortalFreightOrderRequest;
import com.lumen.customer.security.CustomerContext;
import com.lumen.order.dto.CargoRequest;
import com.lumen.order.dto.CargoView;
import com.lumen.order.dto.CreateOrderRequest;
import com.lumen.order.dto.OrderDetailView;
import com.lumen.order.dto.OrderView;
import com.lumen.order.dto.ServiceItemRequest;
import com.lumen.order.dto.ServiceItemView;
import com.lumen.order.service.OrderService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 门户货运委托单 controller 单测 (Iteration 4.6, Task 7).
 *
 * <p><b>为什么本类必须存在</b> —— 计划 Step 7 原本打算不写单测, 把门户 5 个端点
 * 的行为全推给 Task 8 的 IT。但<b>读出侧的内部成本裁剪</b>是靠
 * {@code OrderDetailView.withServiceItems} (39 个组件<b>按位置</b>复制) 实现的 ——
 * 位置传错编译器查不出来, 而且 Task 8 的 IT 跑在 bootstrap 全栈上, 慢且贵。
 * 所以这里用<b>哨兵值</b>把「按位置传参」这件事钉死。
 *
 * <p><b>身份注入方式</b>: {@code PortalIdentity.requireCounterpartyId()} 内部读
 * {@code CustomerContext.requireCustomerId()}, 而 {@code CustomerContext} 是
 * {@code ThreadLocal} 静态容器 —— 所以直接 {@code CustomerContext.set(userId, customerId)}
 * 设上、{@code CustomerContext.clear()} 收尾即可, 不必 mock 静态工具。
 */
class PortalFreightOrderControllerTest {

    private static final Long USER_ID = 7L;
    private static final Long CUSTOMER_ID = 42L;

    private OrderService orderService;
    private PortalFreightOrderController controller;

    @BeforeEach
    void setUp() {
        orderService = mock(OrderService.class);
        controller = new PortalFreightOrderController(orderService);
        CustomerContext.set(USER_ID, CUSTOMER_ID);
    }

    @AfterEach
    void tearDown() {
        CustomerContext.clear();
    }

    // ------------------------------------------------------------ 读出侧裁剪

    /**
     * 门户详情端点**不能**把我方成本 (costAmount) 与我方供应商 (supplierId) 返给客户。
     */
    @Test
    void detail_stripsInternalCostFromServiceItems_keepsTheRest() {
        OrderDetailView detail = detailWithCost();
        when(orderService.getOwned(1L, CUSTOMER_ID)).thenReturn(detail);

        OrderDetailView got = controller.detail(1L).getData();

        assertThat(got.serviceItems()).hasSize(1);
        ServiceItemView item = got.serviceItems().get(0);
        // 内部成本被摘掉
        assertThat(item.supplierId()).isNull();
        assertThat(item.costAmount()).isNull();
        // 其余 12 个组件原样保留
        assertThat(item.id()).isEqualTo(9001L);
        assertThat(item.feeType()).isEqualTo("OCEAN_FREIGHT");
        assertThat(item.feeName()).isEqualTo("海运费");
        assertThat(item.feeCategory()).isEqualTo("FREIGHT");
        assertThat(item.calcBasis()).isEqualTo("PER_CONTAINER");
        assertThat(item.qty()).isEqualByComparingTo("2");
        assertThat(item.unitPrice()).isEqualByComparingTo("1500.00");
        assertThat(item.amount()).isEqualByComparingTo("3000.00");
        assertThat(item.currencyCode()).isEqualTo("CNY");
        assertThat(item.isMandatory()).isEqualTo(1);
        assertThat(item.source()).isEqualTo("INTERNAL");
        assertThat(item.remark()).isEqualTo("整柜");
        // 详情的其它 38 个组件原样保留
        assertThat(got.orderNo()).isEqualTo("ORD-20261110-000001");
        assertThat(got.customerId()).isEqualTo(CUSTOMER_ID);
        assertThat(got.orderStatus()).isEqualTo("DRAFT");
        assertThat(got.allowedNextStates()).containsExactly("PENDING_CONFIRM");
        assertThat(got.cargo()).hasSize(1);
    }

    /** confirm / cancel 也返回 OrderDetailView —— 同样不能带成本。 */
    @Test
    void confirm_stripsInternalCostFromServiceItems() {
        when(orderService.confirmOwned(1L, CUSTOMER_ID)).thenReturn(detailWithCost());

        OrderDetailView got = controller.confirm(1L).getData();

        assertThat(got.serviceItems().get(0).supplierId()).isNull();
        assertThat(got.serviceItems().get(0).costAmount()).isNull();
        assertThat(got.orderNo()).isEqualTo("ORD-20261110-000001");
    }

    @Test
    void cancel_stripsInternalCostFromServiceItems() {
        when(orderService.cancelOwned(1L, CUSTOMER_ID, "客户临时改期")).thenReturn(detailWithCost());

        OrderDetailView got = controller.cancel(1L, Map.of("reason", "客户临时改期")).getData();

        assertThat(got.serviceItems().get(0).supplierId()).isNull();
        assertThat(got.serviceItems().get(0).costAmount()).isNull();
        assertThat(got.orderNo()).isEqualTo("ORD-20261110-000001");
    }

    // ------------------------------------------------------------ 哨兵测试

    /**
     * <b>哨兵测试</b> —— {@code OrderDetailView.withServiceItems} 按<b>位置</b>复制
     * 39 个组件, 同类型参数传错位置编译器查不出来 (全是 String / LocalDateTime)。
     * 所以给每个组件一个<b>可区分</b>的哨兵值 (同类型的也不同), 复制后逐一断言。
     *
     * <p>这条测试在「把 businessType 与 tradeDirection 写反」时必须转红。
     */
    @Test
    void withServiceItems_sentinel_keepsAllOther38Components() {
        OrderDetailView original = sentinelDetail();
        List<ServiceItemView> newItems = List.of(
                new ServiceItemView(9001L, "OCEAN_FREIGHT", "海运费", "FREIGHT", "PER_CONTAINER",
                        new BigDecimal("2"), new BigDecimal("1500.00"), new BigDecimal("3000.00"),
                        "CNY", null, null, 1, "INTERNAL", "整柜"));

        OrderDetailView copy = original.withServiceItems(newItems);

        // --- 38 个组件逐一等于原值 (serviceItems 除外) ---
        assertThat(copy.id()).isEqualTo(original.id());
        assertThat(copy.orderNo()).isEqualTo(original.orderNo());
        assertThat(copy.customerId()).isEqualTo(original.customerId());
        assertThat(copy.businessType()).isEqualTo(original.businessType());
        assertThat(copy.tradeDirection()).isEqualTo(original.tradeDirection());
        assertThat(copy.incoterms()).isEqualTo(original.incoterms());
        assertThat(copy.serviceType()).isEqualTo(original.serviceType());
        assertThat(copy.originAddress()).isEqualTo(original.originAddress());
        assertThat(copy.destAddress()).isEqualTo(original.destAddress());
        assertThat(copy.totalQty()).isEqualByComparingTo(original.totalQty());
        assertThat(copy.totalWeightKg()).isEqualByComparingTo(original.totalWeightKg());
        assertThat(copy.totalVolumeCbm()).isEqualByComparingTo(original.totalVolumeCbm());
        assertThat(copy.orderStatus()).isEqualTo(original.orderStatus());
        assertThat(copy.settlementCurrency()).isEqualTo(original.settlementCurrency());
        assertThat(copy.etd()).isEqualTo(original.etd());
        assertThat(copy.eta()).isEqualTo(original.eta());
        assertThat(copy.createdAt()).isEqualTo(original.createdAt());
        assertThat(copy.cargoReadyDate()).isEqualTo(original.cargoReadyDate());
        assertThat(copy.containerQty()).isEqualTo(original.containerQty());
        assertThat(copy.containerTypeCode()).isEqualTo(original.containerTypeCode());
        assertThat(copy.marksAndNos()).isEqualTo(original.marksAndNos());
        assertThat(copy.cargoDescription()).isEqualTo(original.cargoDescription());
        assertThat(copy.isDangerous()).isEqualTo(original.isDangerous());
        assertThat(copy.isOversized()).isEqualTo(original.isOversized());
        assertThat(copy.temperatureRange()).isEqualTo(original.temperatureRange());
        assertThat(copy.remark()).isEqualTo(original.remark());
        assertThat(copy.cancelReason()).isEqualTo(original.cancelReason());
        assertThat(copy.closeReason()).isEqualTo(original.closeReason());
        assertThat(copy.voidReason()).isEqualTo(original.voidReason());
        assertThat(copy.submittedAt()).isEqualTo(original.submittedAt());
        assertThat(copy.confirmedAt()).isEqualTo(original.confirmedAt());
        assertThat(copy.deliveredAt()).isEqualTo(original.deliveredAt());
        assertThat(copy.settledAt()).isEqualTo(original.settledAt());
        assertThat(copy.closedAt()).isEqualTo(original.closedAt());
        assertThat(copy.createdBy()).isEqualTo(original.createdBy());
        assertThat(copy.updatedBy()).isEqualTo(original.updatedBy());
        assertThat(copy.cargo()).isEqualTo(original.cargo());
        assertThat(copy.allowedNextStates()).isEqualTo(original.allowedNextStates());
        // --- 被替换的那一个 ---
        assertThat(copy.serviceItems()).isEqualTo(newItems);
    }

    /**
     * {@code ServiceItemView.withoutInternalCost} —— 只置空 supplierId / costAmount,
     * 其余 12 个组件原样保留。
     */
    @Test
    void withoutInternalCost_nullsOnlySupplierIdAndCostAmount() {
        ServiceItemView item = new ServiceItemView(9001L, "OCEAN_FREIGHT", "海运费", "FREIGHT",
                "PER_CONTAINER", new BigDecimal("2"), new BigDecimal("1500.00"),
                new BigDecimal("3000.00"), "CNY", 77L, new BigDecimal("1200.00"), 1,
                "INTERNAL", "整柜");

        ServiceItemView stripped = item.withoutInternalCost();

        assertThat(stripped.supplierId()).isNull();
        assertThat(stripped.costAmount()).isNull();
        assertThat(stripped.id()).isEqualTo(item.id());
        assertThat(stripped.feeType()).isEqualTo(item.feeType());
        assertThat(stripped.feeName()).isEqualTo(item.feeName());
        assertThat(stripped.feeCategory()).isEqualTo(item.feeCategory());
        assertThat(stripped.calcBasis()).isEqualTo(item.calcBasis());
        assertThat(stripped.qty()).isEqualByComparingTo(item.qty());
        assertThat(stripped.unitPrice()).isEqualByComparingTo(item.unitPrice());
        assertThat(stripped.amount()).isEqualByComparingTo(item.amount());
        assertThat(stripped.currencyCode()).isEqualTo(item.currencyCode());
        assertThat(stripped.isMandatory()).isEqualTo(item.isMandatory());
        assertThat(stripped.source()).isEqualTo(item.source());
        assertThat(stripped.remark()).isEqualTo(item.remark());
    }

    // ------------------------------------------------------------ 写入侧 + 归属边界

    /** 建单时也必须抹掉内部成本 —— 客户不能写我方成本。 */
    @Test
    void create_stripsInternalCostFromSubmittedServiceItems() {
        when(orderService.createForCustomer(eq(CUSTOMER_ID), any()))
                .thenReturn(new OrderView(1L, "ORD-20261110-000002", CUSTOMER_ID,
                        "SEA", "IMPORT", "FOB", "DOOR_TO_DOOR",
                        "上海", "洛杉矶",
                        new BigDecimal("2"), new BigDecimal("1000.00"), new BigDecimal("5.00"),
                        "DRAFT", "CNY", LocalDate.of(2026, 1, 15), LocalDate.of(2026, 2, 16),
                        LocalDateTime.of(2026, 1, 10, 9, 0)));

        controller.create(portalRequest());

        ArgumentCaptor<CreateOrderRequest> captor = ArgumentCaptor.forClass(CreateOrderRequest.class);
        verify(orderService).createForCustomer(eq(CUSTOMER_ID), captor.capture());
        CreateOrderRequest sent = captor.getValue();
        assertThat(sent.customerId()).isEqualTo(CUSTOMER_ID);
        assertThat(sent.serviceItems()).hasSize(1);
        assertThat(sent.serviceItems().get(0).supplierId()).isNull();
        assertThat(sent.serviceItems().get(0).costAmount()).isNull();
        assertThat(sent.serviceItems().get(0).feeName()).isEqualTo("海运费");
        assertThat(sent.businessType()).isEqualTo("SEA");
    }

    /** 列表端点必须按门户身份里的 customerId 查, 而不是 body / query 里的任何东西。 */
    @Test
    void list_usesCustomerIdFromPortalContext() {
        PageResult<OrderView> page = PageResult.of(List.of(), 0L, 1L, 20L);
        when(orderService.pageByCustomer(CUSTOMER_ID, 1L, 20L, null)).thenReturn(page);

        controller.list(1L, 20L, null);

        verify(orderService).pageByCustomer(CUSTOMER_ID, 1L, 20L, null);
    }

    // ------------------------------------------------------------ fixtures

    private static OrderDetailView detailWithCost() {
        return new OrderDetailView(
                1L, "ORD-20261110-000001", CUSTOMER_ID,
                "SEA", "IMPORT", "FOB", "DOOR_TO_DOOR",
                "上海", "洛杉矶",
                new BigDecimal("2"), new BigDecimal("1000.00"), new BigDecimal("5.00"),
                "DRAFT", "CNY",
                LocalDate.of(2026, 1, 15), LocalDate.of(2026, 2, 16),
                LocalDateTime.of(2026, 1, 10, 9, 0),
                LocalDate.of(2026, 1, 12), 1, "40HQ",
                "N/M", "钢材",
                0, 1, "常温",
                "门户自建单", null, null, null,
                null, null, null, null, null,
                7L, 7L,
                List.of(new CargoView(5001L, "钢材", "STEEL", "7208", "STEEL_COIL",
                        new BigDecimal("2"), "卷", new BigDecimal("1000.00"),
                        new BigDecimal("980.00"), new BigDecimal("5.00"),
                        new BigDecimal("600.00"), new BigDecimal("200.00"), new BigDecimal("200.00"),
                        1, new BigDecimal("50000.00"), "CNY", "宝钢", "Q235B",
                        0, null, null, "卷装")),
                List.of(new ServiceItemView(9001L, "OCEAN_FREIGHT", "海运费", "FREIGHT",
                        "PER_CONTAINER", new BigDecimal("2"), new BigDecimal("1500.00"),
                        new BigDecimal("3000.00"), "CNY", 77L, new BigDecimal("1200.00"), 1,
                        "INTERNAL", "整柜")),
                List.of("PENDING_CONFIRM"));
    }

    /**
     * 39 个组件各给一个<b>可区分</b>的哨兵值 —— 同类型的组件也用不同的值,
     * 这样「按位置传参写反」一定被下面的断言抓到。
     */
    private static OrderDetailView sentinelDetail() {
        return new OrderDetailView(
                101L,                          // 1  id
                "S-02",                        // 2  orderNo
                103L,                          // 3  customerId
                "S-04",                        // 4  businessType
                "S-05",                        // 5  tradeDirection
                "S-06",                        // 6  incoterms
                "S-07",                        // 7  serviceType
                "S-08",                        // 8  originAddress
                "S-09",                        // 9  destAddress
                new BigDecimal("110.1"),       // 10 totalQty
                new BigDecimal("111.2"),       // 11 totalWeightKg
                new BigDecimal("112.3"),       // 12 totalVolumeCbm
                "S-13",                        // 13 orderStatus
                "S-14",                        // 14 settlementCurrency
                LocalDate.of(2026, 1, 15),     // 15 etd
                LocalDate.of(2026, 2, 16),     // 16 eta
                LocalDateTime.of(2026, 1, 15, 10, 30),   // 17 createdAt
                LocalDate.of(2026, 3, 17),     // 18 cargoReadyDate
                19,                            // 19 containerQty
                "S-20",                        // 20 containerTypeCode
                "S-21",                        // 21 marksAndNos
                "S-22",                        // 22 cargoDescription
                23,                            // 23 isDangerous
                24,                            // 24 isOversized
                "S-25",                        // 25 temperatureRange
                "S-26",                        // 26 remark
                "S-27",                        // 27 cancelReason
                "S-28",                        // 28 closeReason
                "S-29",                        // 29 voidReason
                LocalDateTime.of(2026, 1, 16, 11, 31),   // 30 submittedAt
                LocalDateTime.of(2026, 1, 17, 12, 32),   // 31 confirmedAt
                LocalDateTime.of(2026, 1, 18, 13, 33),   // 32 deliveredAt
                LocalDateTime.of(2026, 1, 19, 14, 34),   // 33 settledAt
                LocalDateTime.of(2026, 1, 20, 15, 35),   // 34 closedAt
                135L,                          // 35 createdBy
                136L,                          // 36 updatedBy
                List.of(new CargoView(5001L, "S-37", "S-38", "S-39", "S-40",
                        new BigDecimal("41.1"), "S-42", new BigDecimal("43.3"),
                        new BigDecimal("44.4"), new BigDecimal("45.5"),
                        new BigDecimal("46.6"), new BigDecimal("47.7"), new BigDecimal("48.8"),
                        49, new BigDecimal("50.50"), "S-51", "S-52", "S-53",
                        54, "S-55", "S-56", "S-57")),   // 37 cargo
                List.of(new ServiceItemView(9001L, "S-58", "S-59", "S-60", "S-61",
                        new BigDecimal("62.2"), new BigDecimal("63.3"), new BigDecimal("64.4"),
                        "S-65", 66L, new BigDecimal("67.7"), 68, "S-69", "S-70")),  // 38 serviceItems
                List.of("S-71", "S-72"));       // 39 allowedNextStates
    }

    private static PortalFreightOrderRequest portalRequest() {
        return new PortalFreightOrderRequest(
                "SEA", "IMPORT", "FOB", "DOOR_TO_DOOR",
                "上海", "洛杉矶",
                LocalDate.of(2026, 1, 12), LocalDate.of(2026, 1, 15), LocalDate.of(2026, 2, 16),
                1, "40HQ", "N/M", "钢材",
                true, "常温", "CNY", "门户自建单",
                List.of(new CargoRequest(
                        "钢材", "STEEL", "7208", "STEEL_COIL", 2.0, "卷",
                        1000.0, 980.0, 5.0, 600.0, 200.0, 200.0,
                        true, 50000.0, "CNY", "宝钢", "Q235B",
                        false, null, null, "卷装")),
                List.of(new ServiceItemRequest(
                        "OCEAN_FREIGHT", "海运费", "FREIGHT", "PER_CONTAINER",
                        2.0, 1500.0, 3000.0, "CNY",
                        77L, 1200.0, true, "整柜")));
    }
}
