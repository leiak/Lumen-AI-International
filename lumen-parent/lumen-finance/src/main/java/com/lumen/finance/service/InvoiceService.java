package com.lumen.finance.service;

import com.lumen.finance.entity.BillingInvoice;

import java.util.List;

/**
 * 应收/应付单据服务 (Iteration 4.3.1, PR3-T3.4).
 *
 * <p>对应 {@code t_billing_invoice}. 提供
 * <ol>
 *   <li>{@link #list} — 分页查询 (tenant + 可选 status 过滤)</li>
 *   <li>{@link #get} — 按 id + tenant 拿单条 invoice</li>
 *   <li>{@link #submit} — DRAFT → PENDING_L1, 写 submitted_at</li>
 *   <li>{@link #l1Approve} — PENDING_L1 → L1_APPROVED, 写 l1ApproverId + l1ApprovedAt + 可选 comment</li>
 *   <li>{@link #l1Reject} — PENDING_L1 → REJECTED, comment 必填, 写 l1ApproverId + l1ApprovedAt + comment</li>
 * </ol>
 *
 * <p>对应 V26 状态机 (4.3.1 仅 1 级审批; 4.3.2 PR2 加 PENDING_L2 / L2_APPROVED).
 * 权限校验 (角色检查) 由 controller 层 {@code @PreAuthorize} 完成, service 仅做数据校验.
 *
 * <p>所有方法都接受 {@code tenantId} 显式参数 (不依赖 TenantContext).
 */
public interface InvoiceService {

    /**
     * 分页列出指定租户的 invoice (tenant + 可选 status 过滤), 按 id desc 排序.
     */
    List<BillingInvoice> list(Long tenantId, String status, int pageNum, int pageSize);

    /**
     * 按 tenant + id 取 invoice. 缺失 / 跨租户 → {@code INVOICE_NOT_FOUND}.
     */
    BillingInvoice get(Long tenantId, Long id);

    /**
     * 提交单据: DRAFT → PENDING_L1, 写 {@code submittedAt=now}.
     *
     * @return 更新后的 invoice
     * @throws com.lumen.finance.exception.FinanceException
     *         {@code INVOICE_NOT_FOUND} / {@code INVALID_STATE_TRANSITION}
     */
    BillingInvoice submit(Long tenantId, Long id, Long operatorId);

    /**
     * 财务初审通过: PENDING_L1 → PENDING_L2, 写 {@code l1ApproverId / l1ApprovedAt / l1Comment}.
     * comment 可为 null. (4.3.2 PR3 起, L1 终态改为 PENDING_L2, 等 L2 终审.)
     *
     * @return 更新后的 invoice
     * @throws com.lumen.finance.exception.FinanceException
     *         {@code INVOICE_NOT_FOUND} / {@code INVALID_STATE_TRANSITION}
     */
    BillingInvoice l1Approve(Long tenantId, Long id, Long operatorId, String comment);

    /**
     * 财务初审驳回: PENDING_L1 → REJECTED, 写 {@code l1ApproverId / l1ApprovedAt / l1Comment}.
     * comment 必填 (非空 + 非空白), 否则抛 {@code INVOICE_REJECT_REASON_REQUIRED}.
     *
     * @return 更新后的 invoice
     * @throws com.lumen.finance.exception.FinanceException
     *         {@code INVOICE_NOT_FOUND} / {@code INVALID_STATE_TRANSITION}
     *         / {@code INVOICE_REJECT_REASON_REQUIRED} (comment missing)
     */
    BillingInvoice l1Reject(Long tenantId, Long id, Long operatorId, String comment);

    /**
     * 财务终审通过: PENDING_L2 → L2_APPROVED, 写 {@code l2ApproverId / l2ApprovedAt / l2Comment}.
     * comment 可为 null. 4.3.2 PR3 起 L1 终态 = PENDING_L2, 此方法做 L2 终审.
     *
     * <p><b>硬约束</b>: 同一操作员不能同时做 L1 和 L2 — 若 {@code operatorId == invoice.l1ApproverId},
     * 抛 {@code L1_L2_SAME_OPERATOR}.
     *
     * @return 更新后的 invoice
     * @throws com.lumen.finance.exception.FinanceException
     *         {@code INVOICE_NOT_FOUND} / {@code INVALID_STATE_TRANSITION}
     *         / {@code L1_L2_SAME_OPERATOR}
     */
    BillingInvoice l2Approve(Long tenantId, Long id, Long operatorId, String comment);

    /**
     * 财务终审驳回: PENDING_L2 → REJECTED, 写 {@code l2ApproverId / l2ApprovedAt / l2Comment}.
     * comment 必填 (非空 + 非空白), 否则抛 {@code INVOICE_REJECT_REASON_REQUIRED}.
     *
     * <p><b>硬约束</b>: 同一操作员不能同时做 L1 和 L2 — 若 {@code operatorId == invoice.l1ApproverId},
     * 抛 {@code L1_L2_SAME_OPERATOR}.
     *
     * @return 更新后的 invoice
     * @throws com.lumen.finance.exception.FinanceException
     *         {@code INVOICE_NOT_FOUND} / {@code INVALID_STATE_TRANSITION}
     *         / {@code INVOICE_REJECT_REASON_REQUIRED} (comment missing)
     *         / {@code L1_L2_SAME_OPERATOR}
     */
    BillingInvoice l2Reject(Long tenantId, Long id, Long operatorId, String comment);
}
