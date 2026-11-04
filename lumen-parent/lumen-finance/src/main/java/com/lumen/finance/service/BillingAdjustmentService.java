package com.lumen.finance.service;

import com.lumen.finance.dto.AdjustmentDtos.CreateRequest;
import com.lumen.finance.entity.BillingAdjustment;

/**
 * Facade over {@link AdjustmentService} for callers that prefer a single-object
 * {@link CreateRequest} (Iteration 4.4 PR2).
 *
 * <p>Used by reconciliation auto-adjustments where {@code sourceInvoiceId} may not be
 * known up front. The facade resolves an anchor invoice:
 * <ul>
 *   <li>{@code sourceInvoiceId} given → used as-is, no lookup;</li>
 *   <li>else {@code counterpartyId} given → narrow to that counterparty's own
 *       <b>RECEIVABLE</b> L2_APPROVED invoices (so the anchor can never land on
 *       another merchant's document); if that search is empty, <b>fall back to the
 *       tenant-level search</b> (tenant + L2_APPROVED + currency) rather than
 *       throwing — the 4.4 reconciliation flow must not fail for a merchant that
 *       has a variance but no L2_APPROVED invoice of its own;</li>
 *   <li>else → tenant-level search (tenant + L2_APPROVED + currency), lowest id wins.</li>
 * </ul>
 * If no candidate exists at all → {@code FinanceErrorCode.BAD_REQUEST}.
 *
 * <p>The tenant-level fallback is only safe because the merchant portal resolves
 * ownership from {@code t_reconciliation.counterparty_id} (the claim table), not from
 * {@code source_invoice_id}. Do not "tighten" the fallback to throw without
 * re-checking that read-side rule.
 *
 * <p>For the standard controller flow that already has a concrete source invoice, callers
 * should prefer {@link AdjustmentService#create} directly to keep the sourceInvoiceId
 * explicit and the call cheap.
 */
public interface BillingAdjustmentService {

    /**
     * Create an adjustment (DRAFT) using a {@link CreateRequest}.
     *
     * @param tenantId tenant id
     * @param req      request (sourceInvoiceId optional; resolved if absent)
     * @return persisted BillingAdjustment (status=DRAFT)
     */
    BillingAdjustment create(Long tenantId, CreateRequest req);
}