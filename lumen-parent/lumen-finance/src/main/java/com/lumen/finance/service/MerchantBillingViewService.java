package com.lumen.finance.service;

import com.lumen.finance.dto.portal.MerchantDtos.AdjustmentView;
import com.lumen.finance.dto.portal.MerchantDtos.InvoiceView;
import com.lumen.finance.dto.portal.MerchantDtos.PaymentView;
import com.lumen.finance.dto.portal.MerchantDtos.ReconView;
import com.lumen.finance.dto.portal.MerchantPage;
import com.lumen.finance.exception.FinanceErrorCode;

/**
 * 商家视角的账单只读查询 (Iteration 4.5C-2).
 *
 * <p><b>为什么不直接复用 {@code ReconciliationService.list} /
 * {@code PaymentRecordService.list}</b>: 那两个方法的 {@code LambdaQueryWrapper}
 * 里既没有 {@code counterparty_id} 也没有 {@code tenant_id} —— 收了参数不用。
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
}