package com.lumen.finance.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

import java.util.List;

/**
 * Reconciliation (对账 + 自动调账) DTOs (Iteration 4.4 PR2, 4.4 PR2-extension controllers).
 *
 * <p>{@link RunRequest} is consumed by {@code BillingReconciliationController.run} — service-level
 * validation rejects blank / non-{@code YYYY-MM} periods and currencies that aren't 3 chars.
 * {@link ResolveRequest} / {@link WaiveRequest} are consumed by the per-id POST endpoints, where
 * the id is taken from the path and the body holds only the operator-supplied note / reason.
 * {@link Result} is the rich return shape of the legacy 2-arg {@code runReconciliation} overload;
 * the controller-facing 4-arg {@code runReconciliation} returns void and discards it.
 */
public class ReconciliationDtos {

    @Data
    public static class RunRequest {
        @NotBlank
        @Pattern(regexp = "^\\d{4}-(0[1-9]|1[0-2])$")
        private String period;

        @NotBlank
        @Size(min = 3, max = 3)
        private String currency;
    }

    @Data
    public static class ResolveRequest {
        /** Optional free-text note from the operator (e.g. "已与客户确认"). */
        @Size(max = 500)
        private String resolutionNote;
    }

    @Data
    public static class WaiveRequest {
        /** Required waiver reason. Service enforces {@code >= ADJUSTMENT_REASON_MIN_LEN} (10). */
        @NotBlank
        @Size(min = 10, max = 500)
        private String waiveReason;
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
