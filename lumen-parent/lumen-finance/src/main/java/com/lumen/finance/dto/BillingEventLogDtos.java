package com.lumen.finance.dto;

import java.time.LocalDateTime;

/**
 * BillingEventLog DTO (Iteration 4.3.1, PR3-T3.5).
 *
 * <p>事件溯源 — 幂等 + 失败留痕. controller list 端点 (admin only) 用此出参.
 *
 * <p>Java 17 record; 字段顺序对齐 spec §3.3 + admin 排查需求.
 */
public final class BillingEventLogDtos {
    private BillingEventLogDtos() {}

    /**
     * 事件溯源视图 — controller list (admin only) 出参.
     *
     * <p>字段含义:
     * <ul>
     *   <li>{@code id} — 主键</li>
     *   <li>{@code tenantId} — 多租户隔离键</li>
     *   <li>{@code sourceEventId} — 业务事件 ID (幂等键)</li>
     *   <li>{@code businessType} / {@code businessId} — 业务维度</li>
     *   <li>{@code processStatus} — RECEIVED / SUCCESS / FAILED / NO_RULE</li>
     *   <li>{@code errorMsg} — 处理失败原因 (FAILED 时有值)</li>
     *   <li>{@code receivedAt} — listener 接收时间</li>
     *   <li>{@code processedAt} — listener 处理完成时间 (可能为 null, e.g. NO_RULE 仍在 pending)</li>
     * </ul>
     */
    public record View(
        Long id,
        Long tenantId,
        String sourceEventId,
        String businessType,
        Long businessId,
        String processStatus,
        String errorMsg,
        LocalDateTime receivedAt,
        LocalDateTime processedAt
    ) {}
}