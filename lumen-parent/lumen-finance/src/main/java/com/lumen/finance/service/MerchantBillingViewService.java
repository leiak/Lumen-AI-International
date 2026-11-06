package com.lumen.finance.service;

import com.lumen.finance.dto.portal.MerchantDtos.AdjustmentView;
import com.lumen.finance.dto.portal.MerchantDtos.DisputeRequest;
import com.lumen.finance.dto.portal.MerchantDtos.DisputeResult;
import com.lumen.finance.dto.portal.MerchantDtos.InvoiceView;
import com.lumen.finance.dto.portal.MerchantDtos.PaymentView;
import com.lumen.finance.dto.portal.MerchantDtos.ReconView;
import com.lumen.finance.dto.portal.MerchantPage;
import com.lumen.finance.exception.FinanceErrorCode;

/**
 * 商家视角的账单查询 (Iteration 4.5C-2)。
 *
 * <p>主体是<b>只读</b>查询; 4.5C-2 Task 3 起多了一条写路径
 * ({@link #raiseDispute} —— 商家提异议) 与两条导出路径。
 *
 * <p><b>为什么不直接复用 {@code ReconciliationService.list} /
 * {@code PaymentRecordService.list}</b>: 这两个方法的 {@code LambdaQueryWrapper}
 * 里<b>都没有 {@code counterparty_id}</b> —— 收了参数不用。
 * ({@code PaymentRecordService.list} 在 4.5C-2.1 缺口 #8 之后已经带上了
 * {@code tenant_id}, 但那只是<b>租户</b>维度的纵深防御, <b>挡不住跨商家</b> ——
 * 同一租户下 A 商家仍能翻到 B 商家的流水。)
 * 对内是"财务看全部"的既定语义, 对外是越权面。窄接口把商家隔离做成
 * <b>实现细节而不是调用约定</b>: controller 传进来的 {@code counterpartyId}
 * 一定是当前登录商家的 (取自 JWT), 没有第二个值可传。
 *
 * <p><b>三条可见性铁律</b> (每个查询方法都必须满足, 缺一条就是越权):
 * <ol>
 *   <li>{@code tenant_id = :tenantId} —— 收参数不用是最常见的漏洞形态</li>
 *   <li>{@code counterparty_id = :counterpartyId} —— 商家只看自己的</li>
 *   <li>{@code direction = 'RECEIVABLE'} —— 商家门户只呈现应收侧; 应付是平台付给别人的账</li>
 * </ol>
 *
 * <p>软删除另说: {@code BillingReconciliation} / {@code BillingPaymentRecord}
 * 的实体<b>没有</b> {@code deleted} 字段 (DDL 有), 且全仓未配 MyBatis-Plus 全局逻辑删除,
 * 所以这两张表必须显式 {@code .apply("deleted = 0")}。{@code BillingInvoice} 有
 * {@code @TableLogic}, 反而<b>不能</b>手写 —— 写了会与 MP 追加的条件重复。
 */
public interface MerchantBillingViewService {

    /** 分页查对账单 (期间可选, null = 全期可见)。 */
    MerchantPage<ReconView> listReconciliations(Long tenantId, Long counterpartyId, String period, long page, long size);

    /**
     * 查单条对账单。
     *
     * @throws com.lumen.finance.exception.FinanceException {@link FinanceErrorCode#RECONCILIATION_NOT_FOUND} —
     *         行不存在<b>与</b>行不属于该商家走同一码, 不泄漏别人的资源是否存在
     */
    ReconView getReconciliation(Long tenantId, Long counterpartyId, Long id);

    /** 分页查发票 (期间 / 状态均可选, null = 不过滤)。 */
    MerchantPage<InvoiceView> listInvoices(Long tenantId, Long counterpartyId, String period, String status, long page, long size);

    /**
     * 查单张发票。
     *
     * @throws com.lumen.finance.exception.FinanceException {@link FinanceErrorCode#INVOICE_NOT_FOUND}
     */
    InvoiceView getInvoice(Long tenantId, Long counterpartyId, Long id);

    /** 分页查付款记录 (期间可选, null = 全期可见)。 */
    MerchantPage<PaymentView> listPayments(Long tenantId, Long counterpartyId, String period, long page, long size);

    /**
     * 分页查"我的调账单"。
     *
     * <p>{@code t_billing_adjustment} <b>既没有 {@code counterparty_id} 也没有 {@code period}</b>,
     * 直接查必然越权。归属靠两跳还原: (a) {@code t_reconciliation.adjustment_id} ——
     * 月结跑出来的自动调账单; (b) {@code t_billing_invoice.source_invoice_id} ——
     * 发票上挂的调账单。两跳都先按 {@code counterparty_id} 收口。
     *
     * <p>⚠️ 已知缺口: 只覆盖这两条来源链。将来若出现第三种建调账单的入口,
     * 必须回到这里补第三跳, 否则商家会看不到自己有权看到的调账单。
     */
    MerchantPage<AdjustmentView> listMyAdjustments(Long tenantId, Long counterpartyId, long page, long size);

    /**
     * 查单张调账单 (归属校验同 {@link #listMyAdjustments})。
     *
     * @throws com.lumen.finance.exception.FinanceException {@link FinanceErrorCode#ADJUSTMENT_NOT_FOUND}
     */
    AdjustmentView getMyAdjustment(Long tenantId, Long counterpartyId, Long adjustmentId);

    /**
     * 商家对某期对账单的差异提异议 (Iteration 4.5C-2 核心写路径)。
     *
     * <p><b>不新建调账单</b>。{@code ReconciliationServiceImpl.runReconciliation} 已经为
     * 每笔非零差异建好了一张 DRAFT 调账单并挂在 {@code adjustment_id} 上 —— 商家要表达的是
     * 「我不认可这个差额」, 不是「再调一次账」。再建一张就是对同一笔差异双重调账。
     * 本方法做的事是: 把商家主张 + 理由写进 recon, 并把那张已存在的 DRAFT 推进到
     * {@code PENDING_L1} 进审批。
     *
     * <p>入参校验分两层, 别混:
     * <ul>
     *   <li><b>形状校验</b> (reason 非空、够长且不超长 / amount 落在 DECIMAL(18,4) 可表示范围内,
     *       <b>允许负数</b> —— 负差额是合法主张, 但 0 无意义) 在门户 controller 做, 抛
     *       {@code PortalErrorCode} —— 那是 HTTP 层的请求契约, 不该让 finance 知道门户</li>
     *   <li><b>业务校验</b> (期间<b>未</b>闭期 / recon 状态非 {@code ADJUSTMENT_CREATED} /
     *       无关联调账单 / 调账单状态不可用 / 已异议过) 在这里做,
     *       抛 {@link FinanceErrorCode#RECON_NOT_DISPUTABLE}</li>
     * </ul>
     *
     * <p><b>闭期方向</b>: 只有该期<b>已 CLOSED</b> 才可提异议 (spec §7.2 step 2) ——
     * 期间还在 OPEN / IN_PROGRESS 时数字还会变, 此时记下来的主张没有意义。
     *
     * @throws com.lumen.finance.exception.FinanceException 不可异议 (409_005) 或对账单不存在 (404_006)
     */
    DisputeResult raiseDispute(Long tenantId, Long counterpartyId, Long userId,
                               DisputeRequest request);

    /** 导出发票为 xlsx (period / status 可为 null = 全量)。 */
    byte[] exportInvoices(Long tenantId, Long counterpartyId, String period, String status);

    /** 导出对账单为 xlsx (period 可为 null = 全期)。 */
    byte[] exportReconciliations(Long tenantId, Long counterpartyId, String period);
}