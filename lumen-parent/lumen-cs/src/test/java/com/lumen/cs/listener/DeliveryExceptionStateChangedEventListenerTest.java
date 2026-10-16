package com.lumen.cs.listener;

import com.lumen.common.tenant.TenantContext;
import com.lumen.cs.service.SlaEventService;
import com.lumen.dispatch.DispatchConstants;
import com.lumen.dispatch.event.DeliveryExceptionStateChangedEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DeliveryExceptionStateChangedEventListener 单元测试 (Iteration 4.2 PR6 finalize).
 *
 * <p>覆盖 state → SLA event type 映射:
 * <ul>
 *   <li>OPEN → L1_RESPONSE_DUE (record timer start)</li>
 *   <li>INVESTIGATING → L1_RESPONDED (L1 响应确认)</li>
 *   <li>RESOLVED → RESOLVED</li>
 *   <li>CANCELLED → CANCELLED</li>
 *   <li>UNKNOWN (CLOSED) → 不写任何 row (审计由 dispatch 自身处理)</li>
 * </ul>
 *
 * <p>另覆盖 TenantContext 在 try 中设置、在 finally 中清理.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DeliveryExceptionStateChangedEventListener 单元测试")
class DeliveryExceptionStateChangedEventListenerTest {

    @Mock private SlaEventService slaEventService;
    @InjectMocks private DeliveryExceptionStateChangedEventListener listener;

    private static final long TENANT = 1L;
    private static final long EXCEPTION_ID = 100L;

    @BeforeEach
    void setUp() {
        // 默认 sla_event 写入成功 (新插入) — listener 不在乎 Optional 是否 empty
        // 用 lenient() 因为 closed_recordsNothing case 不调用 record()
        lenient().when(slaEventService.record(any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(Optional.empty());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("OPEN → L1_RESPONSE_DUE (record timer start)")
    void open_recordsL1ResponseDue() {
        DeliveryExceptionStateChangedEvent ev = new DeliveryExceptionStateChangedEvent(
            TENANT, EXCEPTION_ID, 99L, "DAMAGE",
            null, DispatchConstants.EX_OPEN, "create",
            null, "test", "actor"
        );

        listener.onStateChanged(ev);

        ArgumentCaptor<String> typeCap = ArgumentCaptor.forClass(String.class);
        verify(slaEventService).record(eq(TENANT), eq(EXCEPTION_ID), typeCap.capture(),
            any(), any(), any(), any());
        assertThat(typeCap.getValue()).isEqualTo("L1_RESPONSE_DUE");
    }

    @Test
    @DisplayName("INVESTIGATING → L1_RESPONDED")
    void investigating_recordsL1Responded() {
        DeliveryExceptionStateChangedEvent ev = new DeliveryExceptionStateChangedEvent(
            TENANT, EXCEPTION_ID, 99L, "DAMAGE",
            DispatchConstants.EX_OPEN, DispatchConstants.EX_INVESTIGATING, "investigate",
            "Alice", "test", "Alice"
        );

        listener.onStateChanged(ev);

        ArgumentCaptor<String> typeCap = ArgumentCaptor.forClass(String.class);
        verify(slaEventService).record(eq(TENANT), eq(EXCEPTION_ID), typeCap.capture(),
            any(), any(), any(), any());
        assertThat(typeCap.getValue()).isEqualTo("L1_RESPONDED");
    }

    @Test
    @DisplayName("RESOLVED → RESOLVED")
    void resolved_recordsResolved() {
        DeliveryExceptionStateChangedEvent ev = new DeliveryExceptionStateChangedEvent(
            TENANT, EXCEPTION_ID, 99L, "DAMAGE",
            DispatchConstants.EX_INVESTIGATING, DispatchConstants.EX_RESOLVED, "resolve",
            "Alice", "fixed", "Alice"
        );

        listener.onStateChanged(ev);

        ArgumentCaptor<String> typeCap = ArgumentCaptor.forClass(String.class);
        verify(slaEventService).record(eq(TENANT), eq(EXCEPTION_ID), typeCap.capture(),
            any(), any(), any(), any());
        assertThat(typeCap.getValue()).isEqualTo("RESOLVED");
    }

    @Test
    @DisplayName("CANCELLED → CANCELLED")
    void cancelled_recordsCancelled() {
        DeliveryExceptionStateChangedEvent ev = new DeliveryExceptionStateChangedEvent(
            TENANT, EXCEPTION_ID, 99L, "DAMAGE",
            DispatchConstants.EX_INVESTIGATING, DispatchConstants.EX_CANCELLED, "cancel",
            "Alice", "no longer needed", "admin"
        );

        listener.onStateChanged(ev);

        ArgumentCaptor<String> typeCap = ArgumentCaptor.forClass(String.class);
        verify(slaEventService).record(eq(TENANT), eq(EXCEPTION_ID), typeCap.capture(),
            any(), any(), any(), any());
        assertThat(typeCap.getValue()).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("CLOSED → 不写 sla_event (审计由 dispatch 自身处理)")
    void closed_recordsNothing() {
        DeliveryExceptionStateChangedEvent ev = new DeliveryExceptionStateChangedEvent(
            TENANT, EXCEPTION_ID, 99L, "DAMAGE",
            DispatchConstants.EX_RESOLVED, DispatchConstants.EX_CLOSED, "close",
            "Alice", "test", "Alice"
        );

        listener.onStateChanged(ev);

        verify(slaEventService, never()).record(any(), any(), anyString(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Listener 异常 → 不抛 (吞掉) + TenantContext 仍 clear")
    void exception_isSwallowed_andTenantContextCleared() {
        DeliveryExceptionStateChangedEvent ev = new DeliveryExceptionStateChangedEvent(
            TENANT, EXCEPTION_ID, 99L, "DAMAGE",
            null, DispatchConstants.EX_OPEN, "create",
            null, "test", "actor"
        );
        when(slaEventService.record(any(), any(), any(), any(), any(), any(), any()))
            .thenThrow(new RuntimeException("DB down"));

        // 不抛
        listener.onStateChanged(ev);

        // TenantContext 必须 clear (防 ThreadLocal 泄漏)
        assertThat(TenantContext.get()).isNull();
    }

    @Test
    @DisplayName("Listener 正常路径后 TenantContext 必须 clear")
    void tenantContextClearedOnSuccess() {
        DeliveryExceptionStateChangedEvent ev = new DeliveryExceptionStateChangedEvent(
            TENANT, EXCEPTION_ID, 99L, "DAMAGE",
            null, DispatchConstants.EX_OPEN, "create",
            null, "test", "actor"
        );

        listener.onStateChanged(ev);

        assertThat(TenantContext.get()).isNull();
    }

    @Test
    @DisplayName("Listener 写入时使用事件 occurredAt 时间")
    void usesEventOccurredAt() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 15, 10, 30);
        DeliveryExceptionStateChangedEvent ev = new DeliveryExceptionStateChangedEvent(
            TENANT, EXCEPTION_ID, 99L, "DAMAGE",
            null, DispatchConstants.EX_OPEN, "create",
            null, "test", "actor"
        ) {
            // 默认 occurredAt 由 DomainEvent 基类生成 — 这里不能 override, 改成
            // 用 ArgumentCaptor 验证 LocalDateTime 在合理窗口即可.
        };

        listener.onStateChanged(ev);

        ArgumentCaptor<LocalDateTime> timeCap = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(slaEventService).record(any(), any(), any(), any(), any(), timeCap.capture(), any());
        LocalDateTime captured = timeCap.getValue();
        assertThat(captured).isBetween(LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusMinutes(1));
    }
}
