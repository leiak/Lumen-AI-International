package com.lumen.finance.service;

import com.lumen.finance.dto.AdjustmentDtos.CreateRequest;
import com.lumen.finance.entity.BillingAdjustment;

/**
 * Facade over {@link AdjustmentService} for callers that prefer a single-object
 * {@link CreateRequest} (Iteration 4.4 PR2).
 *
 * <p>Used by reconciliation auto-adjustments where {@code sourceInvoiceId} may not be
 * known up front — the facade will resolve a matching L2_APPROVED invoice for the
 * (direction, counterpartyId, currency) tuple, or reject with BAD_REQUEST if none exists.
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