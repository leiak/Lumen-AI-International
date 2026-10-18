package com.lumen.finance.service;

import com.lumen.finance.dto.ReconciliationDtos.Result;
import com.lumen.finance.entity.BillingReconciliation;

import java.util.List;

/**
 * Reconciliation (对账 + 自动调账) service (Iteration 4.4 PR2 + 4.4 PR2-extension controllers).
 *
 * <p>{@link #runReconciliation} compares expected amounts (sum of L2_APPROVED invoices) against
 * actual amounts (sum of {@code t_payment_record} rows for the requested period) on a
 * (direction, counterpartyId, currency) key. Each variance triggers an auto-adjustment via
 * {@link BillingAdjustmentService} and writes a {@code t_reconciliation} row.
 *
 * <p>4.4 simplification: the expected side is NOT filtered by period (matches the MonthlyClosing
 * snapshot strategy); the {@code period} parameter is recorded on the {@code t_reconciliation}
 * row and used to filter the payment_records side.
 *
 * <p>Two flavors of {@code runReconciliation} exist:
 * <ul>
 *   <li>{@link #runReconciliation(Long, String)} — legacy 2-arg, returns rich {@link Result}
 *       (used by tests and any future caller that needs the variance breakdown).</li>
 *   <li>{@link #runReconciliation(Long, String, String, Long)} — controller-facing 4-arg,
 *       accepts {@code currency} (optional filter) and {@code userId} (operator on duty),
 *       returns void. Result details are still logged for auditability.</li>
 * </ul>
 */
public interface ReconciliationService {

    /** Fetch a reconciliation by id. Missing → FinanceException(NOT_FOUND). */
    BillingReconciliation get(Long tenantId, Long id);

    /** Page through reconciliations; optional period filter; ordered by id desc. */
    List<BillingReconciliation> list(Long tenantId, String period, int page, int size);

    /**
     * List reconciliations for a period with optional direction / status filters.
     * Returns the full unpaginated list (controller renders as JSON array).
     *
     * <p>Tenant-scoped: rows whose {@code tenantId != tenantId} are excluded.
     * Either filter can be null/blank to skip that constraint.
     */
    List<BillingReconciliation> listByPeriod(Long tenantId, String period, String direction, String status);

    /**
     * Run reconciliation for the given period. Returns rich {@link Result} (variance list,
     * auto-adjustment ids, counters). Used directly by tests and any caller that needs the
     * breakdown; controllers prefer the 4-arg overload.
     *
     * <p>Idempotent: if a prior ADJUSTMENT_CREATED row exists for the same
     * (period, direction, counterpartyId, currency) tuple, it is skipped.
     */
    Result runReconciliation(Long tenantId, String period);

    /**
     * Controller-facing run: same algorithm as the 2-arg overload. The {@code currency} (when
     * non-blank) restricts the candidate set to invoices/payments of that currency; {@code userId}
     * is logged for audit. Returns void because the controller's {@code R<Void>} response shape
     * doesn't surface the result; callers can fetch details via {@link #listByPeriod}.
     */
    void runReconciliation(Long tenantId, String period, String currency, Long userId);

    /**
     * Mark a reconciliation as {@code RESOLVED} with an operator note (optional).
     * Idempotent on already-RESOLVED rows. Missing id → FinanceException(NOT_FOUND).
     */
    BillingReconciliation resolveReconciliation(Long tenantId, Long id, String note, Long userId);

    /**
     * Waive a reconciliation variance (mark WAIVED with reason). Requires
     * reason.length() &gt;= {@code ADJUSTMENT_REASON_MIN_LEN}. Logs {@code userId} for audit.
     */
    BillingReconciliation waive(Long tenantId, Long id, String reason);

    /**
     * Controller-facing waive overload: same rules as {@link #waive}, additionally logs
     * {@code userId} so the audit trail records who approved the waiver.
     */
    BillingReconciliation waiveReconciliation(Long tenantId, Long id, String reason, Long userId);
}
