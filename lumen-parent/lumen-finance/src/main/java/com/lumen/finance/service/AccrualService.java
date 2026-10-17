package com.lumen.finance.service;

import com.lumen.finance.entity.BillingAccrual;
import com.lumen.finance.entity.BillingInvoice;

import java.util.List;

/**
 * 暂估单服务 (Iteration 4.3.1, PR3-T3.3).
 *
 * <p>对应 {@code t_billing_accrual} + {@code t_billing_invoice}. 提供
 * <ol>
 *   <li>{@link #list} — 分页查询 (tenant + 可选 businessType/status 过滤)</li>
 *   <li>{@link #get} — 按 id + tenant 拿单条 accrual</li>
 *   <li>{@link #finalize} — 终态化: OPEN → LOCKED → 生成 invoice (DRAFT) → CONVERTED,
 *       同步创建 {@link BillingInvoice} 草稿, 后续单据走 1 级审批 (T3.4)</li>
 *   <li>{@link #release} — 释放: OPEN → RELEASED (用于本期不计入收入的场景)</li>
 * </ol>
 *
 * <p>所有方法都接受 {@code tenantId} 显式参数（不依赖 TenantContext），便于 batch /
 * 跨租户后台任务调用.
 *
 * <p>状态机迁移由 {@code com.lumen.extension.state.StateMachineEngine#assertTransition}
 * 校验; 非法迁移 → {@link com.lumen.finance.exception.FinanceErrorCode#INVALID_STATE_TRANSITION}.
 *
 * <p>单据号生成在 service 内部内联生成 {@code {AR|AP}-{tenantId}-{epochMs}-{seq}} — 不引入
 * {@code lumen-numbering} 依赖, 4.3.2 阶段再统一迁移到 NumberGenerator.
 */
public interface AccrualService {

    /**
     * 分页列出指定租户的 accrual (tenant + 可选 businessType/status 过滤), 按 id desc 排序.
     */
    List<BillingAccrual> list(Long tenantId, String businessType, String status,
                              int pageNum, int pageSize);

    /**
     * 按 tenant + id 取 accrual. 缺失 → {@code ACCRUAL_NOT_FOUND}.
     */
    BillingAccrual get(Long tenantId, Long id);

    /**
     * 终态化 accrual: OPEN → LOCKED → 生成 invoice (DRAFT) → CONVERTED.
     *
     * <p>必须在 {@code status=OPEN} 时调用; 否则抛 {@code INVALID_STATE_TRANSITION}.
     *
     * @return 新生成的 {@link BillingInvoice} (status=DRAFT)
     * @throws com.lumen.finance.exception.FinanceException
     *         {@code ACCRUAL_NOT_FOUND} / {@code INVALID_STATE_TRANSITION}
     *         / {@code INVOICE_NO_CONFLICT}
     */
    BillingInvoice finalize(Long tenantId, Long accrualId, Long operatorId);

    /**
     * 释放 accrual: OPEN → RELEASED, 写入 {@code errorMsg=reason} (留痕).
     *
     * @return 更新后的 accrual
     * @throws com.lumen.finance.exception.FinanceException
     *         {@code ACCRUAL_NOT_FOUND} / {@code INVALID_STATE_TRANSITION}
     */
    BillingAccrual release(Long tenantId, Long accrualId, String reason, Long operatorId);
}
