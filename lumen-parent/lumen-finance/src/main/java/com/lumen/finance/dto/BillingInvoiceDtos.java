package com.lumen.finance.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * BillingInvoice DTO (Iteration 4.3.1, PR3-T3.5).
 *
 * <p>应收/应付正式单据的控制器视图. 对应 {@code t_billing_invoice}.
 *
 * <p>Java 17 record; 字段顺序对齐 spec §3.5 + controller wire contract.
 * 与 entity 区别: 不暴露 {@code deleted}/{@code version}; 增加 L1 审批元信息;
 * {@code submittedAt} 单列 (entity 也有).
 */
public final class BillingInvoiceDtos {
    private BillingInvoiceDtos() {}

    /**
     * 单据视图 — controller list/get/submit/l1-approve/l1-reject 通用出参.
     *
     * <p>字段含义:
     * <ul>
     *   <li>{@code id} — 主键</li>
     *   <li>{@code tenantId} — 多租户隔离键</li>
     *   <li>{@code invoiceNo} — 单据号 (e.g. {@code AR-1-1737100000-1})</li>
     *   <li>{@code direction} — RECEIVABLE / PAYABLE</li>
     *   <li>{@code counterpartyId} / {@code counterpartyType} — 客户/承运商</li>
     *   <li>{@code businessType} — LAST_MILE / WAREHOUSE_IN / WAREHOUSE_OUT</li>
     *   <li>{@code sourceAccrualId} — 来自哪张暂估单 (UNIQUE)</li>
     *   <li>{@code grossAmount} / {@code taxAmount} / {@code netAmount}</li>
     *   <li>{@code currency}</li>
     *   <li>{@code status} — DRAFT / PENDING_L1 / L1_APPROVED / PUSHED / REJECTED</li>
     *   <li>{@code ruleId} / {@code ruleVersion} — 规则快照</li>
     *   <li>{@code submittedAt} — DRAFT → PENDING_L1 时间</li>
     *   <li>{@code l1ApproverId} / {@code l1ApprovedAt} / {@code l1Comment} — 1 级审批</li>
     *   <li>{@code l2ApproverId} / {@code l2ApprovedAt} / {@code l2Comment} — 预留 (4.3.2 PR2 用, 4.3.1 全 null)</li>
     *   <li>{@code errorMsg} — 处理失败原因 (若有)</li>
     *   <li>{@code createdAt} — 创建时间</li>
     * </ul>
     */
    public record View(
        Long id,
        Long tenantId,
        String invoiceNo,
        String direction,
        Long counterpartyId,
        String counterpartyType,
        String businessType,
        Long sourceAccrualId,
        BigDecimal grossAmount,
        BigDecimal taxAmount,
        BigDecimal netAmount,
        String currency,
        String status,
        Long ruleId,
        Integer ruleVersion,
        LocalDateTime submittedAt,
        Long l1ApproverId,
        LocalDateTime l1ApprovedAt,
        String l1Comment,
        Long l2ApproverId,
        LocalDateTime l2ApprovedAt,
        String l2Comment,
        String errorMsg,
        LocalDateTime createdAt
    ) {}
}