package com.lumen.finance.dto.portal;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 商家门户只读视图 DTO (Iteration 4.5C-2).
 *
 * <p><b>字段裁剪是这一层存在的全部理由</b>。这些 record 是从实体手工映射出来的,
 * 不是直接序列化实体 —— 实体里混着内部字段 ({@code ruleId} / {@code l1ApproverId} /
 * {@code errorMsg} / {@code sourceAccrualId} 之类), 直接返回等于把内部计费规则、
 * 审批人 ID 和报错栈泄露给外部商家。映射函数是这张网的唯一收口处。
 *
 * <p>{@code DisputeRequest} / {@code DisputeResult} 在 4.5C-2 Task 3 才加。
 */
public final class MerchantDtos {

    private MerchantDtos() {
    }

    /**
     * 发票视图。
     *
     * <p>不裁掉的: {@code fxRateAtCreation} —— 商家看的是外币计价单据,
     * 汇率是他理解金额的前提, 裁掉等于让他无法自洽对账。
     *
     * @param period 拼好的 {@code yyyy-MM}; {@code periodYear}/{@code periodMonth}
     *               任一为空时为 null (该单据未归属会计期间)
     */
    public record InvoiceView(
            Long id,
            String invoiceNo,
            String period,
            String businessType,
            BigDecimal grossAmount,
            BigDecimal taxAmount,
            BigDecimal netAmount,
            String currency,
            BigDecimal fxRateAtCreation,
            String status,
            LocalDateTime createdAt) {
    }

    /**
     * 对账单视图。
     *
     * @param closed 该会计期间是否已 CLOSED。商家在闭期前可提异议, 闭期后只读 ——
     *               这是拒绝分支的可见形态, 提前告诉他比让他点了才知道强
     * @param disputeAmount 商家主张金额; null = 只质疑不主张金额
     * @param disputeAt     异议时间; 非空即该行已被异议过 (前端据此禁用再提)
     */
    public record ReconView(
            Long id,
            String period,
            String currency,
            BigDecimal expectedAmount,
            BigDecimal actualAmount,
            BigDecimal varianceAmount,
            Long adjustmentId,
            String reconciliationStatus,
            String remark,
            BigDecimal disputeAmount,
            String disputeReason,
            LocalDateTime disputeAt,
            boolean closed) {
    }

    /** 付款记录视图。 */
    public record PaymentView(
            Long id,
            String period,
            String currency,
            BigDecimal amount,
            String sourceRef,
            LocalDate paymentDate,
            String source,
            String remark) {
    }

    /**
     * 调账单视图。
     *
     * <p>带 {@code l1Comment} / {@code l2Comment} 是刻意的 —— 商家提了异议,
     * 审批驳回时必须让他看到驳回理由, 否则异议就进了黑洞。裁掉这两个字段等于
     * 静默吞掉商家的话。
     *
     * <p>不带的: {@code l1ApproverId} / {@code l2ApproverId} (内部审批人 ID)、
     * {@code errorMsg} (提交时刻的时间戳串, 不是业务信息)、{@code fxRateAtCreation}。
     */
    public record AdjustmentView(
            Long id,
            String adjustmentNo,
            String adjustmentType,
            BigDecimal amount,
            String currency,
            BigDecimal grossAmount,
            BigDecimal taxAmount,
            BigDecimal netAmount,
            String status,
            String reason,
            LocalDateTime createdAt,
            String l1Comment,
            String l2Comment) {
    }
}