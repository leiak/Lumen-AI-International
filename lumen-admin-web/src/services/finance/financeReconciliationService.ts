/**
 * Lumen Admin — Finance reconciliation service (Iteration 4.4 PR3).
 *
 * Wraps the backend `BillingReconciliationController` at
 * `/api/v1/finance/reconciliations/*`. 4 endpoints (Iteration 4.4
 * PR2-extension, Task 2.4):
 *
 *   1. `run()`     — POST  /api/v1/finance/reconciliations/run
 *                    (trigger reconciliation; body: `{ period, currency }`.
 *                    Service returns void on success — caller should follow
 *                    up with `list()` to read the generated rows.)
 *   2. `list()`    — GET   /api/v1/finance/reconciliations/list
 *                    (filter: period (required) / direction / status.
 *                    Returns flat list ordered by
 *                    (period, direction, counterpartyId) asc.)
 *   3. `resolve()` — POST  /api/v1/finance/reconciliations/{id}/resolve
 *                    (mark RESOLVED; body: `{ resolutionNote }` optional.
 *                    Idempotent: already-RESOLVED rows return unchanged.)
 *   4. `waive()`   — POST  /api/v1/finance/reconciliations/{id}/waive
 *                    (mark WAIVED; body: `{ waiveReason }` required,
 *                    ≥ 10 chars.)
 *
 * Per-action perms (V30 RBAC seed, 4.4 PR2-extension):
 *   `finance:reconciliation:run`     — run
 *   `finance:reconciliation:view`    — list
 *   `finance:reconciliation:resolve` — resolve
 *   `finance:reconciliation:waive`   — waive
 *
 * Status enum: ADJUSTMENT_CREATED / RESOLVED / WAIVED.
 *
 * See `lumen-parent/lumen-finance/.../controller/BillingReconciliationController.java`.
 */

import { request } from '@/services/request';
import type { ApiEnvelope } from '@/services/request';

import { asData } from './_util';

/** Local alias for backend `R<T>` envelope — mirrors sibling CS modules. */
type R<T> = ApiEnvelope<T>;

// ---------------------------------------------------------------------------
// Entity / VO types — mirror backend `BillingReconciliation` + DTOs.
// ---------------------------------------------------------------------------

export type ReconciliationStatus =
  | 'ADJUSTMENT_CREATED'
  | 'RESOLVED'
  | 'WAIVED';

export type ReconciliationDirection = 'RECEIVABLE' | 'PAYABLE';

/** Mirrors `com.lumen.finance.entity.BillingReconciliation`. */
export interface BillingReconciliation {
  id: number;
  tenantId?: number;
  /** `YYYY-MM` form. */
  period: string;
  direction: ReconciliationDirection | string;
  counterpartyId: number;
  counterpartyType?: string;
  currency: string;
  /** BigDecimal on backend, serialised as JSON number. */
  expectedAmount: number;
  actualAmount: number;
  /** expectedAmount − actualAmount (BigDecimal on backend). */
  varianceAmount: number;
  /** id of the auto-generated adjustment row (nullable when RESOLVED). */
  adjustmentId?: number | null;
  reconciliationStatus: ReconciliationStatus | string;
  remark?: string;
  createdAt?: string;
  updatedAt?: string;
}

/** Mirrors `ReconciliationDtos.RunRequest`. */
export interface RunReconciliationRequest {
  /** `YYYY-MM`. */
  period: string;
  /** 3-char ISO 4217 currency. */
  currency: string;
}

/** Mirrors `ReconciliationDtos.ResolveRequest`. */
export interface ResolveReconciliationRequest {
  /** Optional free-text note from the operator. */
  resolutionNote?: string;
}

/** Mirrors `ReconciliationDtos.WaiveRequest`. */
export interface WaiveReconciliationRequest {
  /** Required; backend enforces ≥ 10 chars. */
  waiveReason: string;
}

// ---------------------------------------------------------------------------
// Service namespace
// ---------------------------------------------------------------------------

export const financeReconciliationService = {
  /**
   * POST /finance/reconciliations/run — trigger reconciliation.
   *
   * Service-level idempotency: existing (period, direction,
   * counterpartyId, currency) tuples are skipped, so re-running for the
   * same period is safe. Returns void on success; caller should follow
   * up with `list()` to read the latest rows.
   */
  run: async (data: RunReconciliationRequest): Promise<void> => {
    await asData<void>(
      request.post<R<void>>(
        '/api/v1/finance/reconciliations/run',
        data,
      ),
    );
  },

  /**
   * GET /finance/reconciliations/list — filter by period (required) +
   * optional direction / status.
   *
   * Backend is unpaged and returns a flat `List<BillingReconciliation>`
   * (no total in the wire shape). We wrap into `{ records, total }` so
   * the ProTable adapter can map it consistently with sibling
   * invoice/payment/closing services.
   */
  list: async (
    params: {
      period: string;
      direction?: ReconciliationDirection | string;
      status?: ReconciliationStatus | string;
    },
  ): Promise<{ records: BillingReconciliation[]; total: number }> => {
    const data = await asData<BillingReconciliation[]>(
      request.get<R<BillingReconciliation[]>>(
        '/api/v1/finance/reconciliations/list',
        { params },
      ),
    );
    return { records: data, total: data.length };
  },

  /**
   * POST /finance/reconciliations/{id}/resolve — mark RESOLVED.
   *
   * `resolutionNote` is optional; existing remarks are preserved and the
   * new note is appended (`original | resolve:<note>`). Idempotent.
   */
  resolve: (
    id: number,
    resolutionNote?: string,
  ): Promise<BillingReconciliation> => {
    const body: ResolveReconciliationRequest = {};
    if (resolutionNote) {
      body.resolutionNote = resolutionNote;
    }
    return asData<BillingReconciliation>(
      request.post<R<BillingReconciliation>>(
        `/api/v1/finance/reconciliations/${id}/resolve`,
        body,
      ),
    );
  },

  /**
   * POST /finance/reconciliations/{id}/waive — mark WAIVED.
   * `waiveReason` is required (≥ 10 chars); backend hard-rejects empty.
   */
  waive: (
    id: number,
    waiveReason: string,
  ): Promise<BillingReconciliation> =>
    asData<BillingReconciliation>(
      request.post<R<BillingReconciliation>>(
        `/api/v1/finance/reconciliations/${id}/waive`,
        { waiveReason },
      ),
    ),
};
