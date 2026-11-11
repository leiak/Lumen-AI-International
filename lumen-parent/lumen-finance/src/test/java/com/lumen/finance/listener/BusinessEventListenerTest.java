package com.lumen.finance.listener;

import com.lumen.extension.outbox.DomainEvent;
import com.lumen.finance.FinanceConstants;
import com.lumen.finance.dto.BillingEventDto;
import com.lumen.finance.service.BillingEngineService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * BusinessEventListener 单元测试 (Iteration 4.3.1, PR3-T3.2).
 *
 * <p>纯 Mockito 测试，不启 Spring. {@link BillingEngineService} mock 掉;
 * 事件用本地 stub (与 lumen-dispatch 同名) 让 {@code getClass().getSimpleName()}
 * 走通 listener 的 switch 分支.
 *
 * <p>覆盖 5 个核心场景:
 * <ol>
 *   <li>DeliveryOrderStateChangedEvent SIGNED → engine.accrue 被调用, DTO 字段映射正确</li>
 *   <li>DeliveryOrderStateChangedEvent DISPATCHED → 静默跳过, engine.accrue 不被调用</li>
 *   <li>非 DeliveryOrder 事件 (e.g. DeliveryException) → 静默跳过, engine.accrue 不被调用</li>
 *   <li>OrderStateChangedEvent DELIVERED → engine.accrue 被调用, DTO 字段映射正确 (4.6)</li>
 *   <li>OrderStateChangedEvent 非 DELIVERED → 静默跳过, engine.accrue 不被调用 (4.6)</li>
 * </ol>
 *
 * <p><b>本单测不覆盖跨模块的 getter 契约</b> —— 事件用的是本地 stub, 碰不到
 * {@code com.lumen.order.event.OrderStateChangedEvent} 真实类。那份契约由 bootstrap 的
 * {@code OrderBillingAccrualIT} 端到端守住, 详见下面 stub 类的 javadoc。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BusinessEventListener 单元测试")
class BusinessEventListenerTest {

    @Mock private BillingEngineService engine;
    private BusinessEventListener listener;

    @BeforeEach
    void setUp() {
        listener = new BusinessEventListener(engine);
    }

    // ---------- 1. SIGNED → engine.accrue 被调用 ----------

    @Test
    @DisplayName("DeliveryOrderStateChangedEvent SIGNED → engine.accrue(DTO) 被调用, 字段映射正确")
    void onEvent_deliveryOrderSigned_callsAccrue() {
        DeliveryOrderStateChangedEvent event = new DeliveryOrderStateChangedEvent(
            1001L,                                    // tenantId
            DeliveryOrderStateChangedEvent.DO_SIGNED, // toState
            12345L,                                   // orderId
            new BigDecimal("12.5"),                   // totalWeightKg
            3                                         // stopCount
        );

        listener.onEvent(event);

        ArgumentCaptor<BillingEventDto> cap = ArgumentCaptor.forClass(BillingEventDto.class);
        verify(engine).accrue(cap.capture());
        BillingEventDto dto = cap.getValue();
        assertThat(dto.tenantId()).isEqualTo(1001L);
        assertThat(dto.businessType()).isEqualTo(FinanceConstants.BIZ_LAST_MILE);
        assertThat(dto.businessId()).isEqualTo(12345L);
        assertThat(dto.direction()).isEqualTo(FinanceConstants.DIR_RECEIVABLE);
        assertThat(dto.counterpartyType()).isEqualTo(FinanceConstants.CP_CUSTOMER);
        // env 提取: weight=12.5, quantity=3, amount=0, distance=null (没设 getter)
        assertThat(dto.env()).containsEntry("weight", new BigDecimal("12.5"));
        assertThat(dto.env()).containsEntry("quantity", 3);
        assertThat(dto.env()).containsEntry("amount", BigDecimal.ZERO);
        assertThat(dto.env()).doesNotContainKey("distance");
        // sourceEventId 来自 DomainEvent.eventId (UUID)
        assertThat(dto.sourceEventId()).isNotBlank();
    }

    // ---------- 2. DISPATCHED → 静默 ----------

    @Test
    @DisplayName("DeliveryOrderStateChangedEvent DISPATCHED → 静默跳过, 不调 accrue")
    void onEvent_deliveryOrderDispatched_skipped() {
        DeliveryOrderStateChangedEvent event = new DeliveryOrderStateChangedEvent(
            1001L, "DISPATCHED", 12345L, new BigDecimal("5"), 1);

        listener.onEvent(event);

        verify(engine, never()).accrue(any(BillingEventDto.class));
    }

    // ---------- 3. 非 DeliveryOrder 事件 → 静默 ----------

    @Test
    @DisplayName("未知事件类型 → 静默跳过, engine.accrue 不被调用")
    void onEvent_unknownEventType_skipped() {
        // stub class simpleName = "DeliveryExceptionStateChangedEvent" → 不匹配 listener switch
        DeliveryExceptionStateChangedEvent event = new DeliveryExceptionStateChangedEvent(1001L);

        listener.onEvent(event);

        verifyNoInteractions(engine);
    }

    // ---------- 4. OrderStateChangedEvent DELIVERED → engine.accrue 被调用 ----------

    @Test
    @DisplayName("OrderStateChangedEvent DELIVERED → engine.accrue(DTO) 被调用, 字段映射正确")
    void onEvent_orderDelivered_callsAccrue() {
        OrderStateChangedEvent event = new OrderStateChangedEvent(
            1001L,                                    // tenantId
            "DELIVERED",                              // toState
            555L,                                     // orderId
            42L,                                      // customerId
            new BigDecimal("1200"),                   // totalWeightKg
            new BigDecimal("12.5"),                   // totalVolumeCbm
            new BigDecimal("88"),                     // totalQty
            new BigDecimal("8600.00")                 // estimatedAmount
        );

        listener.onEvent(event);

        ArgumentCaptor<BillingEventDto> cap = ArgumentCaptor.forClass(BillingEventDto.class);
        verify(engine).accrue(cap.capture());
        BillingEventDto dto = cap.getValue();
        assertThat(dto.tenantId()).isEqualTo(1001L);
        assertThat(dto.businessType()).isEqualTo(FinanceConstants.BIZ_ORDER);
        assertThat(dto.businessId()).isEqualTo(555L);
        assertThat(dto.direction()).isEqualTo(FinanceConstants.DIR_RECEIVABLE);
        assertThat(dto.counterpartyId()).isEqualTo(42L);
        assertThat(dto.counterpartyType()).isEqualTo(FinanceConstants.CP_CUSTOMER);
        assertThat(dto.env()).containsEntry("weight", new BigDecimal("1200"));
        assertThat(dto.env()).containsEntry("quantity", new BigDecimal("88"));
        assertThat(dto.env()).containsEntry("amount", new BigDecimal("8600.00"));
        assertThat(dto.sourceEventId()).isNotBlank();
    }

    // ---------- 5. OrderStateChangedEvent 非 DELIVERED → 静默 ----------

    @Test
    @DisplayName("OrderStateChangedEvent CONFIRMED → 静默跳过, 不调 accrue")
    void onEvent_orderNotDelivered_skipped() {
        OrderStateChangedEvent event = new OrderStateChangedEvent(
            1001L, "CONFIRMED", 555L, 42L,
            new BigDecimal("1200"), new BigDecimal("12.5"), new BigDecimal("88"),
            new BigDecimal("8600.00"));

        listener.onEvent(event);

        verify(engine, never()).accrue(any(BillingEventDto.class));
    }

    // ============================ test stubs ============================

    /**
     * 复用 lumen-dispatch 事件名作为 stub 类名, 让
     * {@code event.getClass().getSimpleName()} 命中 listener switch 分支.
     *
     * <p>不要放在 lumen-dispatch 包下, 以免引入反向依赖. 这里的字段集覆盖
     * listener 反射访问的所有 getter.
     */
    public static class DeliveryOrderStateChangedEvent extends DomainEvent {
        public static final String DO_SIGNED = "SIGNED";
        private final Long tenantId;
        private final String toState;
        private final Long orderId;
        private final BigDecimal totalWeightKg;
        private final Integer stopCount;

        public DeliveryOrderStateChangedEvent(Long tenantId, String toState, Long orderId,
                                              BigDecimal totalWeightKg, Integer stopCount) {
            this.tenantId = tenantId;
            this.toState = toState;
            this.orderId = orderId;
            this.totalWeightKg = totalWeightKg;
            this.stopCount = stopCount;
        }

        public Long getTenantId() { return tenantId; }
        public String getToState() { return toState; }
        public Long getOrderId() { return orderId; }
        public BigDecimal getTotalWeightKg() { return totalWeightKg; }
        public Integer getStopCount() { return stopCount; }

        @Override public String eventType() { return "test.delivery_order.state_changed"; }
        @Override public String aggregateType() { return "WmsDeliveryOrder"; }
        @Override public String aggregateId() { return String.valueOf(orderId); }
        @Override public Long tenantId() { return tenantId; }
    }

    /**
     * 另一个事件类型, simpleName 不在 listener switch 列表, 用于验证"未知事件跳过"分支.
     */
    public static class DeliveryExceptionStateChangedEvent extends DomainEvent {
        private final Long tenantId;
        public DeliveryExceptionStateChangedEvent(Long tenantId) { this.tenantId = tenantId; }
        public Long getTenantId() { return tenantId; }
        @Override public String eventType() { return "test.delivery_exception.state_changed"; }
        @Override public String aggregateType() { return "WmsDeliveryException"; }
        @Override public String aggregateId() { return "0"; }
        @Override public Long tenantId() { return tenantId; }
    }

    /**
     * 货运委托单状态事件 stub (Iteration 4.6).
     *
     * <p><b>类名必须与 lumen-order 的 {@code com.lumen.order.event.OrderStateChangedEvent}
     * 逐字相同</b> —— listener 靠 {@code event.getClass().getSimpleName()} 分派,
     * 名字对不上会落到 switch 的 default 分支返回 null, 事件被静默跳过, 用例会"假绿"。
     *
     * <p><b>嵌套类不影响 {@code getSimpleName()}</b> —— 对嵌套类,
     * {@code getClass().getSimpleName()} 返回的是内层类名 (此处即
     * {@code OrderStateChangedEvent}), 不是 {@code BusinessEventListenerTest$OrderStateChangedEvent},
     * 所以 stub 放在本测试类内部即可命中 listener 的 case。
     *
     * <p><b>getter 一律 {@code getXxx} 命名</b> —— listener 的
     * {@code invokeGetter} 用 {@code cls.getMethod("getXxx")} 取值, 只认
     * {@code get} 前缀的公有方法。下面 8 个 getter 名与真实事件类
     * {@code OrderStateChangedEvent} 上 {@code @Getter} 生成的
     * {@code getTenantId / getToState / getOrderId / getCustomerId /
     * getTotalWeightKg / getTotalVolumeCbm / getTotalQty / getEstimatedAmount}
     * 逐字一致; 真实类另有 {@code getOrderNo / getFromState / getAction / getReason /
     * getCurrencyCode / getActor}, finance 本轮不读, 因此这里不实现。
     *
     * <p><b>构造器签名与真实事件类不同是刻意的</b> —— 真实类有 14 个构造参数,
     * 本 stub 只有 8 个。这不会造成假象: finance 侧只通过反射读 getter,
     * 从不 new 真实事件类; 本 stub 也只在单测里构造, 不参与序列化。
     *
     * <p><b>跨模块 getter 契约的兜底由 bootstrap 的
     * {@code OrderBillingAccrualIT} 承担</b>
     * ({@code lumen-bootstrap/src/test/java/com/lumen/integration/OrderBillingAccrualIT.java}):
     * 本 stub 只覆盖 finance 侧的映射逻辑, <b>碰不到真实事件类</b> ——
     * 真实事件改了 getter 名, 本单测照样绿 (它只对着 stub 读)。
     * 那条 IT 走 HTTP → order 模块发<b>真实</b>的 {@code OrderStateChangedEvent} →
     * outbox → {@code OutboxDispatcher} 反序列化 → finance listener 反射读 getter →
     * accrue → 写 {@code t_billing_accrual}, 所以真实 getter 改名会把它打红。
     * <br>
     * <b>但它的覆盖面是部分的</b> (如实记录, 别当成全覆盖):
     * 该 IT 断言 {@code business_id}(getOrderId)、{@code counterparty_id}(getCustomerId)、
     * {@code gross_amount}(getTotalWeightKg, 规则表达式 {@code "weight * 2"} × 100kg = 200)
     * 以及 DELIVERED 才触发 (getToState);
     * <b>getTotalQty / getEstimatedAmount / getTotalVolumeCbm 三个 getter 没有被断言</b> ——
     * 只改这三个的名字, 该 IT 不会红。
     */
    public static class OrderStateChangedEvent extends DomainEvent {
        private final Long tenantId;
        private final String toState;
        private final Long orderId;
        private final Long customerId;
        private final BigDecimal totalWeightKg;
        private final BigDecimal totalVolumeCbm;
        private final BigDecimal totalQty;
        private final BigDecimal estimatedAmount;

        public OrderStateChangedEvent(Long tenantId, String toState, Long orderId, Long customerId,
                                      BigDecimal totalWeightKg, BigDecimal totalVolumeCbm,
                                      BigDecimal totalQty, BigDecimal estimatedAmount) {
            this.tenantId = tenantId;
            this.toState = toState;
            this.orderId = orderId;
            this.customerId = customerId;
            this.totalWeightKg = totalWeightKg;
            this.totalVolumeCbm = totalVolumeCbm;
            this.totalQty = totalQty;
            this.estimatedAmount = estimatedAmount;
        }

        public Long getTenantId() { return tenantId; }
        public String getToState() { return toState; }
        public Long getOrderId() { return orderId; }
        public Long getCustomerId() { return customerId; }
        public BigDecimal getTotalWeightKg() { return totalWeightKg; }
        public BigDecimal getTotalVolumeCbm() { return totalVolumeCbm; }
        public BigDecimal getTotalQty() { return totalQty; }
        public BigDecimal getEstimatedAmount() { return estimatedAmount; }

        @Override public String eventType() { return "order.state.changed"; }
        @Override public String aggregateType() { return "ORDER"; }
        @Override public String aggregateId() { return String.valueOf(orderId); }
        @Override public Long tenantId() { return tenantId; }
    }
}
