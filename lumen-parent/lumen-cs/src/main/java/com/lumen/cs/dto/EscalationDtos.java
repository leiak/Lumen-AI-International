package com.lumen.cs.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * SLA 升级相关 DTO 集合 (Iteration 4.2 PR4).
 *
 * <p>含两类 record:
 * <ul>
 *   <li>{@link SlaEventDto} — 单条 SLA 事件 (t_sla_event 行)</li>
 *   <li>{@link ExceptionSlaView} — 异常工单 SLA 视角视图 (CS 客服台查询用)</li>
 * </ul>
 */
public final class EscalationDtos {
    private EscalationDtos() {}

    /**
     * 单条 SLA 事件 DTO. 字段对齐 {@code t_sla_event} 行.
     */
    public record SlaEventDto(
        Long id,
        Long tenantId,
        Long exceptionId,
        String eventType,
        String currentLevel,
        String severity,
        LocalDateTime occurredAt,
        String payload
    ) {}

    /**
     * 异常工单 SLA 视角视图 (Iteration 4.2 PR6 FE /cs/exceptions 用).
     *
     * <p>{@code escalationStatus} 由 FE 计算 (基于
     * {@code businessMinutesElapsed} vs {@code resolveTargetMinutes}),
     * 此处仅供 DTO 契约对齐; 后端目前不直接产出 — 保留字段为兼容契约.
     */
    public record ExceptionSlaView(
        Long exceptionId,
        String type,
        String status,
        String handler,
        String currentLevel,
        String severity,
        Long businessMinutesElapsed,
        Long resolveTargetMinutes,
        String escalationStatus,
        List<SlaEventDto> slaEvents
    ) {}
}
