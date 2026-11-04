package com.lumen.finance.dto;

import jakarta.validation.constraints.*;
import lombok.Data;
import java.math.BigDecimal;

/**
 * Adjustment (credit / debit note) facade DTOs (Iteration 4.4 PR2).
 *
 * <p>{@link CreateRequest} is consumed by {@code BillingAdjustmentService.create(tenantId, req)},
 * a thin facade over {@code AdjustmentService} that resolves an optional
 * {@code sourceInvoiceId} (4.4 reconciliation auto-adjustments may omit it; the facade
 * picks a matching L2_APPROVED invoice, or rejects with BAD_REQUEST if none exists).
 *
 * <p>4.5C-2: the anchor resolution became counterparty-aware. Before that it only
 * filtered {@code (tenant_id, status, currency)} and took the lowest id, so an
 * auto-adjustment raised for counterparty A could be anchored on counterparty B's
 * invoice — which then made B's merchant portal show A's adjustment. Callers that
 * know the counterparty should now pass {@link CreateRequest#counterpartyId}.
 */
public class AdjustmentDtos {

    @Data
    public static class CreateRequest {
        /** Optional. If null, facade will resolve from a matching L2_APPROVED invoice. */
        private Long sourceInvoiceId;
        /**
         * Optional (4.5C-2). The counterparty this adjustment belongs to.
         *
         * <p>When set, the facade narrows the anchor search to this counterparty's own
         * RECEIVABLE invoices, so the anchor can never land on another merchant's
         * document. When that narrowed search comes up empty the facade falls back to
         * the previous tenant-level search (unchanged 4.4 behaviour).
         *
         * <p>Leaving it null keeps the old, tenant-wide behaviour — safe only for
         * callers that genuinely have no counterparty context.
         */
        private Long counterpartyId;
        /** Required. CREDIT or DEBIT. */
        @NotBlank
        private String adjustmentType;
        /** Required, > 0. Treated as gross (含税). */
        @NotNull
        @DecimalMin("0.01")
        private BigDecimal amount;
        /** Optional. If null, inherited from the resolved source invoice. */
        private String currency;
        /** Required, >= 10 chars (matches AdjustmentService rule). */
        @NotBlank
        @Size(min = 10, max = 500)
        private String reason;
        /** Optional. System actor when null. */
        private Long operatorId;
    }
}