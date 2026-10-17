package com.lumen.finance.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * BillingAccrual DTO (Iteration 4.3.1, PR3-T3.5).
 *
 * <p>暂估单的控制器视图; controller 用 entity → DTO 映射后返回, 不直接暴露
 * 实体类 (避免泄漏 {@code deleted}/{@code version}/{@code payloadJson} 等内部字段).
 *
 * <p>Java 17 record; 字段顺序对齐 spec §3.4 + controller wire contract.
 */
public final class BillingAccrualDtos {
    private BillingAccrualDtos() {}

    /**
     * 暂估单视图 — controller list/get/finalize/release 通用出参.
     *
     * <p>字段含义:
     * <ul>
     *   <li>{@code id} — 主键</li>
     *   <li>{@code tenantId} — 多租户隔离键</li>
     *   <li>{@code sourceEventId} — 触发本笔 accrual 的业务事件 ID (幂等键)</li>
     *   <li>{@code businessType} / {@code businessId} — 业务维度</li>
     *   <li>{@code ruleId} — 命中规则</li>
     *   <li>{@code direction} — RECEIVABLE / PAYABLE</li>
     *   <li>{@code counterpartyId} — 客户/承运商 ID</li>
     *   <li>{@code grossAmount} / {@code taxAmount} / {@code netAmount} — 金额</li>
     *   <li>{@code currency} — 币种 (4.3.1 仅 CNY)</li>
     *   <li>{@code status} — OPEN / LOCKED / CONVERTED / RELEASED / OPEN_FAILED</li>
     *   <li>{@code errorMsg} — 处理失败原因 (OPEN_FAILED 时有值)</li>
     *   <li>{@code lockedAt} — OPEN → LOCKED 时间</li>
     *   <li>{@code createdAt} — 创建时间</li>
     * </ul>
     */
    public record View(
        Long id,
        Long tenantId,
        String sourceEventId,
        String businessType,
        Long businessId,
        Long ruleId,
        String direction,
        Long counterpartyId,
        BigDecimal grossAmount,
        BigDecimal taxAmount,
        BigDecimal netAmount,
        String currency,
        String status,
        String errorMsg,
        LocalDateTime lockedAt,
        LocalDateTime createdAt
    ) {}
}