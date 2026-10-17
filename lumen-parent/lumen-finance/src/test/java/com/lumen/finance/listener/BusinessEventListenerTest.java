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
 * <p>覆盖 3 个核心场景:
 * <ol>
 *   <li>DeliveryOrderStateChangedEvent SIGNED → engine.accrue 被调用, DTO 字段映射正确</li>
 *   <li>DeliveryOrderStateChangedEvent DISPATCHED → 静默跳过, engine.accrue 不被调用</li>
 *   <li>非 DeliveryOrder 事件 (e.g. DeliveryException) → 静默跳过, engine.accrue 不被调用</li>
 * </ol>
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
}
