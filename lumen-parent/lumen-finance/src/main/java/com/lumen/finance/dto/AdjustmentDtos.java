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
 * picks the first matching L2_APPROVED invoice for the (direction, counterpartyId, currency)
 * tuple, or rejects with BAD_REQUEST if none exists).
 */
public class AdjustmentDtos {

    @Data
    public static class CreateRequest {
        /** Optional. If null, facade will resolve from a matching L2_APPROVED invoice. */
        private Long sourceInvoiceId;
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