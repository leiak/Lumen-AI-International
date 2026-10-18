package com.lumen.finance.service;

import com.lumen.finance.dto.ReconciliationDtos.Result;
import com.lumen.finance.entity.BillingReconciliation;

import java.util.List;

/**
 * Reconciliation (对账 + 自动调账) service (Iteration 4.4 PR2).
 *
 * <p>{@link #runReconciliation} compares expected amounts (sum of L2_APPROVED invoices) against
 * actual amounts (sum of {@code t_payment_record} rows for the requested period) on a
 * (direction, counterpartyId, currency) key. Each variance triggers an auto-adjustment via
 * {@link BillingAdjustmentService} and writes a {@code t_reconciliation} row.
 *
 * <p>4.4 simplification: the expected side is NOT filtered by period (matches the MonthlyClosing
 * snapshot strategy); the {@code period} parameter is recorded on the {@code t_reconciliation}
 * row and used to filter the payment_records side.
 */
public interface ReconciliationService {

    /** Fetch a reconciliation by id. Missing → FinanceException(NOT_FOUND). */
    BillingReconciliation get(Long tenantId, Long id);

    /** Page through reconciliations; optional period filter; ordered by id desc. */
    List<BillingReconciliation> list(Long tenantId, String period, int page, int size);

    /**
     * Run reconciliation for the given period.
     *
     * <p>Idempotent: if a prior ADJUSTMENT_CREATED row exists for the same
     * (period, direction, counterpartyId, currency) tuple, it is skipped.
     */
    Result runReconciliation(Long tenantId, String period);

    /**
     * Waive a reconciliation variance (mark WAIVED with reason). Requires
     * reason.length() &gt;= {@code ADJUSTMENT_REASON_MIN_LEN}.
     */
    BillingReconciliation waive(Long tenantId, Long id, String reason);
}