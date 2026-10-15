package com.lumen.cs.service;

/**
 * SLA 升级扫描器 (Iteration 4.2 PR4).
 *
 * <p>由 {@link com.lumen.cs.scheduler.SlaEscalationScheduler} 每 5 分钟
 * (可通过 {@code lumen.cs.sla-scan-interval-ms} 调整) 调用 {@link #scan},
 * 遍历所有 active tenant 的 OPEN / INVESTIGATING 异常工单, 对每条工单:
 * <ol>
 *   <li>从 {@code t_sla_event} 找到最近一次 L1_RESPONDED; 没有则从
 *       {@code wms_delivery_exception.created_at} 算起</li>
 *   <li>用 {@link com.lumen.cs.calculator.SlaCalculator} 计算
 *       {@code businessMinutes(now)}</li>
 *   <li>查 {@code t_sla_config} 当前 (severity, level) 配置,
 *       若 {@code businessMinutes > resolveMinutes} 且
 *       {@code escalate_to_level != null} → 升级</li>
 *   <li>写 {@code t_sla_event} (UNIQUE KEY 防并发) + 发
 *       {@link com.lumen.cs.event.ExceptionEscalatedEvent} outbox 事件</li>
 * </ol>
 */
public interface EscalationService {

    /** 扫描所有 active tenant 的 OPEN / INVESTIGATING 工单并按规则升级. */
    void scan();
}
