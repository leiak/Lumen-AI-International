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
 * <p><b>裁剪口径 (四个 view 统一)</b>:
 * <ul>
 *   <li><b>保留</b>审批意见文本 ({@code l1Comment} / {@code l2Comment}) —— 商家提了异议,
 *       审批驳回时必须让他看到驳回理由, 裁掉等于静默吞掉商家的话</li>
 *   <li><b>裁掉</b>审批人 ID ({@code l1ApproverId} / {@code l2ApproverId}) —— 那是内部
 *       操作人标识, 与商家理解自己的单据无关</li>
 *   <li><b>裁掉</b>{@code errorMsg} —— 提交时刻的时间戳串, 不是业务信息</li>
 * </ul>
 *
 * <p>{@code DisputeRequest} / {@code DisputeResult} 是 4.5C-2 Task 3 加的<b>写路径</b> DTO ——
 * 商家提异议的入参与回执。
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
     * <p>{@code l1Comment} / {@code l2Comment} 按统一口径保留: 商家看到自己发票
     * {@code status=REJECTED} 却没有任何拒绝理由, 是和"调账单驳回看不到理由"
     * 一模一样的黑洞。
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
            LocalDateTime createdAt,
            String l1Comment,
            String l2Comment) {
    }

    /**
     * 对账单视图。
     *
     * @param closed 该会计期间是否已 CLOSED。商家<b>只在该期已闭期后才能提异议</b> ——
     *               未闭期时数字还会变, 提了也没有意义; 这是拒绝分支的可见形态,
     *               提前告诉他比让他点了才知道强
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

    /**
     * 付款记录视图。
     *
     * <p>不裁掉的: {@code paymentDate} / {@code sourceRef} —— 商家靠付款日期和银行流水号
     * 核对自己是否已经付过, 这两个字段就是他核对单据的全部依据。
     *
     * <p>裁掉的: {@code createdBy} —— 内部操作人 id (录付款记录的后台用户), 对商家
     * 核账毫无意义, 且属于内部人员标识。实体里还有 {@code counterpartyType} /
     * {@code direction}, 门户侧由"只呈现应收"和"只呈现自己的"两条铁律统一决定,
     * 逐行再带一份只会诱使前端写出按它过滤的逻辑。
     */
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
     *
     * <p><b>刻意不给 {@code sourceInvoiceId}</b>: 锚点 ({@code source_invoice_id})
     * 是内部技术锚点, <b>不是归属依据</b> —— 归属以 recon 行的
     * {@code counterparty_id} 为准。4.5C-2 起锚点已 counterparty-aware
     * ({@code BillingAdjustmentServiceImpl.resolveSourceInvoiceId} 在
     * {@code counterpartyId} 非空时收窄到该商家自己的 RECEIVABLE 发票), 但:
     * (a) 4.5C-2 之前建出来的<b>存量</b>调账单, 锚点仍是当时的租户级锚点, 可能落在
     * 别家单据上; (b) 锚点 id 本身是<b>内部单据 id</b>, 暴露给第三方等于给出别家
     * 单据的 id 空间, 且会诱使前端写出"按 sourceInvoiceId 拼链接"这类逻辑。
     * 对外是否需要展示"这张调账单挂在哪张发票上", 是独立的产品需求, 不在本 task。
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

    /**
     * 商家提异议的请求体。
     *
     * @param reconId        要异议的对账单 id
     * @param claimedAmount 商家主张的应收金额; null = 只质疑不主张金额 (存成 NULL,
     *                       保留财务算出的 {@code variance_amount} 不动)。
     *                       <b>允许负数</b> (商家主张的是差额, 财务算出的差额是负的就该是负的),
     *                       但 0 无意义, 会被拒
     * @param reason        异议理由, 必填, 长度 ≥ {@code ADJUSTMENT_REASON_MIN_LEN} (10)
     */
    public record DisputeRequest(Long reconId, BigDecimal claimedAmount, String reason) {
    }

    /**
     * 提异议的结果。
     *
     * @param reconId          原对账单 id
     * @param adjustmentId     被推进进审批的那张调账单 id (对账单本来就有的, 不是新建的)
     * @param adjustmentStatus 推进后的调账单状态 (正常是 PENDING_L1)
     * @param disputeAt        异议时间
     */
    public record DisputeResult(Long reconId, Long adjustmentId, String adjustmentStatus,
                                LocalDateTime disputeAt) {
    }
}