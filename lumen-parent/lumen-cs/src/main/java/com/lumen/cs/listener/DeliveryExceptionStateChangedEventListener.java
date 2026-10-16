package com.lumen.cs.listener;

import com.lumen.common.tenant.TenantContext;
import com.lumen.cs.CsConstants;
import com.lumen.cs.service.SlaEventService;
import com.lumen.dispatch.DispatchConstants;
import com.lumen.dispatch.event.DeliveryExceptionStateChangedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 异常工单状态机迁移事件监听器 (Iteration 4.2 PR6 finalize).
 *
 * <p>订阅 {@link DeliveryExceptionStateChangedEvent} (lumen-dispatch 在 4.1.x PR2
 * 已落地的 outbox 事件), 把工单状态机的状态迁移映射成 {@code t_sla_event} 行, 供
 * CS 客服台 SLA 视角审计 + 升级计时:
 *
 * <ul>
 *   <li>{@code OPEN} → {@code L1_RESPONSE_DUE} (timer start, 仅首次)</li>
 *   <li>{@code INVESTIGATING} → {@code L1_RESPONDED} (L1 已响应, 计时起点切换)</li>
 *   <li>{@code RESOLVED} → {@code RESOLVED}</li>
 *   <li>{@code CANCELLED} → {@code CANCELLED}</li>
 *   <li>{@code CLOSED} → 不写 sla_event (审计由 dispatch 自身处理)</li>
 * </ul>
 *
 * <p>异步执行: 走 {@code outboxExecutor} (lumen-extension 配置的线程池),
 * 避免阻塞 lumen-dispatch 业务事务.
 *
 * <p><b>TenantContext 处理:</b> 异步 listener 在 {@code outboxExecutor} 线程上
 * 执行, ThreadLocal 不会继承. 必须从事件 {@link DeliveryExceptionStateChangedEvent#getTenantId()}
 * 取值并 set 到 TenantContext, 否则 {@code MybatisPlusConfig} 的
 * {@code TenantLineInnerInterceptor} 会以 tid=0 过滤, 看不到非 tenant=0 行.
 *
 * <p><b>幂等:</b> {@link SlaEventService#record} 依赖 {@code UNIQUE KEY
 * (exception_id, event_type, current_level, deleted)} 防并发重复, 同一工单同一
 * 状态点不会被并发写入多行 — listener 本身无状态, 可被 outbox dispatcher
 * 任意重试.
 *
 * <p><b>失败处理:</b> listener 永不抛出 (catch + log) — 失败由 outbox 兜底
 * 重试链路处理. 这一点与 lumen-dispatch 同款
 * {@link com.lumen.dispatch.listener.ExceptionEscalatedEventListener} 保持一致.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DeliveryExceptionStateChangedEventListener {

    private final SlaEventService slaEventService;

    @EventListener
    @Async("outboxExecutor")
    public void onStateChanged(DeliveryExceptionStateChangedEvent e) {
        try {
            TenantContext.set(e.getTenantId());
            String slaEventType = mapToSlaEventType(e.getToState());
            if (slaEventType == null) {
                // CLOSED 等非 SLA 事件 → 不写 sla_event, 仅记日志
                log.debug("Skip SLA event for exception {} state {} (tenant={}, no SLA mapping)",
                    e.getExceptionId(), e.getToState(), e.getTenantId());
                return;
            }
            // 默认 level=L1; listener 不做 level 推断 (那是 EscalationService 的职责)
            // severity 不传 (PR6 简化版, 写表时由数据库默认 NULL 即可; 后续 PR 可
            // 通过反向 join t_sla_config 或 outbox 携带 severity).
            slaEventService.record(
                e.getTenantId(),
                e.getExceptionId(),
                slaEventType,
                CsConstants.LEVEL_L1,
                null,
                e.getOccurredAt(),
                null
            );
            log.info("Exception {} state {} -> sla_event {} (tenant={})",
                e.getExceptionId(), e.getToState(), slaEventType, e.getTenantId());
        } catch (Exception ex) {
            // listener 永远不抛 — 失败仅记日志, 由 outbox 兜底重试
            log.error("Failed to record SLA event for exception {} state {} (tenant={})",
                e.getExceptionId(), e.getToState(), e.getTenantId(), ex);
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * 工单状态 → SLA event type 映射.
     *
     * @return {@code null} 表示不需要写 sla_event (e.g. CLOSED — dispatch 自身审计已覆盖)
     */
    private static String mapToSlaEventType(String toState) {
        if (toState == null) return null;
        return switch (toState) {
            case DispatchConstants.EX_OPEN          -> CsConstants.SLA_EVENT_L1_RESPONSE_DUE;
            case DispatchConstants.EX_INVESTIGATING -> CsConstants.SLA_EVENT_L1_RESPONDED;
            case DispatchConstants.EX_RESOLVED      -> CsConstants.SLA_EVENT_RESOLVED;
            case DispatchConstants.EX_CANCELLED     -> CsConstants.SLA_EVENT_CANCELLED;
            case DispatchConstants.EX_CLOSED        -> null; // 不写 sla_event
            default -> {
                log.warn("Unknown exception state {} — skip SLA event", toState);
                yield null;
            }
        };
    }
}
