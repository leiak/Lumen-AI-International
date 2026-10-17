package com.lumen.finance.service;

import com.lumen.finance.entity.BillingAdjustment;

import java.math.BigDecimal;
import java.util.List;

/**
 * 调账单 (credit / debit note) 服务 (Iteration 4.3.2, PR4-T4.1).
 *
 * <p>对应 {@code t_billing_adjustment}. 提供完整的 L1 + L2 两级审批状态机 + 过账.
 * 调账单的金额/原因/币种/源单据状态在 {@link #create} 阶段强校验; 后续审批仅校验状态机 +
 * 同人约束.
 *
 * <p>状态机迁移 (与 V30 seed 对齐, 仅校验 {@code fromState -> toState}):
 * <ul>
 *   <li>{@code create}:  生成 {@code DRAFT} 单 (无状态迁移, 仅入库)</li>
 *   <li>{@code submit}: {@code DRAFT      -> PENDING_L1}</li>
 *   <li>{@code l1Approve}: {@code PENDING_L1 -> PENDING_L2}, 写 L1 元信息</li>
 *   <li>{@code l1Reject}: {@code PENDING_L1 -> REJECTED},   comment 必填</li>
 *   <li>{@code l2Approve}: {@code PENDING_L2 -> L2_APPROVED}, 同操作员拦截</li>
 *   <li>{@code l2Reject}: {@code PENDING_L2 -> REJECTED},    comment 必填 + 同操作员拦截</li>
 *   <li>{@code markPosted}: {@code L2_APPROVED -> POSTED} (财务经理手动确认过账)</li>
 * </ul>
 *
 * <p>权限校验 (角色检查) 由 controller 层 {@code @PreAuthorize} 完成; service 只做
 * 数据 + 状态机校验.
 *
 * <p>所有方法都接受 {@code tenantId} 显式参数 (不依赖 TenantContext).
 */
public interface AdjustmentService {

    /**
     * 创建调账单 (DRAFT 态).
     *
     * <p>校验:
     * <ul>
     *   <li>{@code amount > 0} — 否则 {@code ADJUSTMENT_AMOUNT_INVALID}</li>
     *   <li>{@code reason != null && reason.length() >= 10} —
     *       否则 {@code ADJUSTMENT_REASON_REQUIRED}</li>
     *   <li>源单据存在 + 同租户 — 否则 {@code INVOICE_NOT_FOUND} (复用 invoice 错误码)</li>
     *   <li>{@code sourceInvoice.status == L2_APPROVED} — 否则 {@code ADJUSTMENT_SOURCE_NOT_APPROVED}</li>
     *   <li>{@code sourceInvoice.currency} 与调账单币种一致 (4.3.2 不支持跨币种调账) —
     *       否则 {@code ADJUSTMENT_CURRENCY_MISMATCH}. 调账单币种默认继承源单据币种,
     *       调用方传入的 {@code adjustmentType} 仅区分 CREDIT / DEBIT.</li>
     * </ul>
     *
     * <p>税额与净额通过 {@link com.lumen.finance.engine.CurrencyConverter} 计算:
     * <pre>
     *   gross = amount (含税输入)
     *   tax   = CurrencyConverter.calculateTax(amount, taxRate)
     *   net   = amount - tax
     *   fxRate = sourceInvoice.fxRateAtCreation (继承)
     *   taxRate = TaxRateService.getActiveRate(currency, today)
     * </pre>
     *
     * @param tenantId         租户 ID
     * @param sourceInvoiceId  源单据 ID (必须为 L2_APPROVED 态)
     * @param adjustmentType   {@code CREDIT} / {@code DEBIT}
     * @param amount           调整金额 (含税, > 0)
     * @param reason           调账原因 (>= 10 字符)
     * @param operatorId       操作员 ID
     * @return 新建的 adjustment (status=DRAFT, 已含税额/净额/汇率/税率)
     */
    BillingAdjustment create(Long tenantId, Long sourceInvoiceId, String adjustmentType,
                             BigDecimal amount, String reason, Long operatorId);

    /**
     * 提交调账单: DRAFT → PENDING_L1, 写 {@code submittedAt=now} (落 errorMsg 字段,
     * 4.3.2 schema 无独立字段).
     */
    BillingAdjustment submit(Long tenantId, Long adjustmentId, Long operatorId);

    /**
     * 财务初审通过: PENDING_L1 → PENDING_L2, 写 {@code l1ApproverId / l1ApprovedAt / l1Comment}.
     * comment 可为 null.
     */
    BillingAdjustment l1Approve(Long tenantId, Long adjustmentId, Long operatorId, String comment);

    /**
     * 财务初审驳回: PENDING_L1 → REJECTED, comment 必填, 写 {@code l1ApproverId / l1ApprovedAt / l1Comment}.
     */
    BillingAdjustment l1Reject(Long tenantId, Long adjustmentId, Long operatorId, String comment);

    /**
     * 财务终审通过: PENDING_L2 → L2_APPROVED, 写 {@code l2ApproverId / l2ApprovedAt / l2Comment}.
     *
     * <p><b>硬约束</b>: 同一操作员不能同时做 L1 和 L2 — 若 {@code operatorId == adjustment.l1ApproverId},
     * 抛 {@code L1_L2_SAME_OPERATOR}.
     */
    BillingAdjustment l2Approve(Long tenantId, Long adjustmentId, Long operatorId, String comment);

    /**
     * 财务终审驳回: PENDING_L2 → REJECTED, comment 必填, 写 {@code l2ApproverId / l2ApprovedAt / l2Comment}.
     *
     * <p><b>硬约束</b>: 同 {@link #l2Approve}.
     */
    BillingAdjustment l2Reject(Long tenantId, Long adjustmentId, Long operatorId, String comment);

    /**
     * 财务经理手动确认过账: L2_APPROVED → POSTED, 写 {@code errorMsg="posted at ..."} 留痕.
     */
    BillingAdjustment markPosted(Long tenantId, Long adjustmentId, Long operatorId);

    /**
     * 按 tenant + id 取 adjustment. 缺失 / 跨租户 → {@code ADJUSTMENT_NOT_FOUND}.
     */
    BillingAdjustment get(Long tenantId, Long adjustmentId);

    /**
     * 分页列出指定租户的 adjustment (tenant + 可选 status 过滤), 按 id desc 排序.
     */
    List<BillingAdjustment> list(Long tenantId, String status, int page, int size);
}
