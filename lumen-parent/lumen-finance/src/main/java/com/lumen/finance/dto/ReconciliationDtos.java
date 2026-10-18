package com.lumen.finance.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

import java.util.List;

/**
 * Reconciliation (对账 + 自动调账) DTOs (Iteration 4.4 PR2).
 */
public class ReconciliationDtos {

    @Data
    public static class RunRequest {
        @NotBlank
        @Pattern(regexp = "^\\d{4}-(0[1-9]|1[0-2])$")
        private String period;
    }

    @Data
    public static class WaiveRequest {
        @NotNull
        private Long id;
        @NotBlank
        @Size(min = 10, max = 500)
        private String reason;
    }

    @Data
    public static class Result {
        private String period;
        /** Number of (direction, counterpartyId, currency) tuples inspected. */
        private int totalChecked;
        /** Number of variances that triggered an auto-adjustment. */
        private int varianceCount;
        /** Persisted BillingReconciliation rows (one per variance, status=ADJUSTMENT_CREATED). */
        private List<com.lumen.finance.entity.BillingReconciliation> variances;
        /** IDs of the auto-generated BillingAdjustment rows. */
        private List<String> autoAdjustmentIds;
    }
}