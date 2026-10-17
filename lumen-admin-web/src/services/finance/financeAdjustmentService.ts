/**
 * Lumen Admin — Finance adjustment service (Iteration 4.3.2 PR5).
 *
 * Wraps the backend `BillingAdjustmentController` at
 * `/api/v1/finance/adjustments/*`. 9 endpoints (Iteration 4.3.2 PR4-T4.2):
 *
 *   1. `listAdjustments()`     — GET   /api/v1/finance/adjustments
 *   2. `getAdjustment()`       — GET   /api/v1/finance/adjustments/{id}
 *   3. `createAdjustment()`    — POST  /api/v1/finance/adjustments
 *   4. `submitAdjustment()`    — POST  /api/v1/finance/adjustments/{id}/submit
 *                                (DRAFT → PENDING_L1)
 *   5. `approveAdjustmentL1()` — POST  /api/v1/finance/adjustments/{id}/l1-approve
 *                                (PENDING_L1 → PENDING_L2)
 *   6. `rejectAdjustmentL1()`  — POST  /api/v1/finance/adjustments/{id}/l1-reject
 *                                (PENDING_L1 → REJECTED; comment required)
 *   7. `approveAdjustmentL2()` — POST  /api/v1/finance/adjustments/{id}/l2-approve
 *                                (PENDING_L2 → L2_APPROVED; cannot equal L1 approver)
 *   8. `rejectAdjustmentL2()`  — POST  /api/v1/finance/adjustments/{id}/l2-reject
 *                                (PENDING_L2 → REJECTED; comment required)
 *   9. `markAdjustmentPosted()` — POST /api/v1/finance/adjustments/{id}/mark-posted
 *                                (L2_APPROVED → POSTED)
 *
 * Per-action perms (V30 RBAC seed):
 *   `finance:adjustment:view`        — list + get
 *   `finance:adjustment:create`      — create
 *   `finance:adjustment:submit`      — submit
 *   `finance:adjustment:l1_approve`  — l1-approve + l1-reject
 *   `finance:adjustment:l2_approve`  — l2-approve + l2-reject + mark-posted
 *
 * Reject wire-shape: backend reads `comment` from a JSON body map, so we
 * send `{ comment }` even though FE-facing param is `comment` for clarity.
 *
 * Status enum: DRAFT / PENDING_L1 / PENDING_L2 / L2_APPROVED / POSTED / REJECTED.
 *
 * See `lumen-parent/lumen-finance/.../controller/BillingAdjustmentController.java`.
 */

import { request } from '@/services/request';
import type { ApiEnvelope } from '@/services/request';

import type {
  AdjustmentType,
  BillingAdjustment,
} from '@/types/finance';

import { asData } from './_util';

/** Local alias for backend `R<T>` envelope — mirrors sibling CS modules. */
type R<T> = ApiEnvelope<T>;

// ---------------------------------------------------------------------------
// Query / request types
// ---------------------------------------------------------------------------

export interface ListFinanceAdjustmentsParams {
  status?: string;
  page?: number;
  size?: number;
}

export interface CreateAdjustmentRequest {
  sourceInvoiceId: number;
  adjustmentType: AdjustmentType;
  amount: number;
  reason: string;
}

// ---------------------------------------------------------------------------
// Service namespace
// ---------------------------------------------------------------------------

export const financeAdjustmentService = {
  /**
   * GET /finance/adjustments — paged list (filter: status).
   *
   * Backend returns a flat `List<BillingAdjustment>`; total count is NOT in
   * the wire shape. Wrap into `{ records, total }` so ProTable adapter
   * can map it consistently.
   */
  listAdjustments: async (
    params: ListFinanceAdjustmentsParams,
  ): Promise<{ records: BillingAdjustment[]; total: number }> => {
    const data = await asData<BillingAdjustment[]>(
      request.get<R<BillingAdjustment[]>>('/api/v1/finance/adjustments', {
        params,
      }),
    );
    return { records: data, total: data.length };
  },

  /** GET /finance/adjustments/{id} — detail. */
  getAdjustment: (id: number): Promise<BillingAdjustment> =>
    asData<BillingAdjustment>(
      request.get<R<BillingAdjustment>>(`/api/v1/finance/adjustments/${id}`),
    ),

  /** POST /finance/adjustments — create (DRAFT). Body is JSON. */
  createAdjustment: (data: CreateAdjustmentRequest): Promise<BillingAdjustment> =>
    asData<BillingAdjustment>(
      request.post<R<BillingAdjustment>>('/api/v1/finance/adjustments', data),
    ),

  /** POST /finance/adjustments/{id}/submit — DRAFT → PENDING_L1. */
  submitAdjustment: (id: number): Promise<BillingAdjustment> =>
    asData<BillingAdjustment>(
      request.post<R<BillingAdjustment>>(
        `/api/v1/finance/adjustments/${id}/submit`,
        {},
      ),
    ),

  /**
   * POST /finance/adjustments/{id}/l1-approve — PENDING_L1 → PENDING_L2.
   * `comment` is optional (server treats null as "no comment").
   */
  approveAdjustmentL1: (
    id: number,
    comment?: string,
  ): Promise<BillingAdjustment> =>
    asData<BillingAdjustment>(
      request.post<R<BillingAdjustment>>(
        `/api/v1/finance/adjustments/${id}/l1-approve`,
        comment ? { comment } : {},
      ),
    ),

  /**
   * POST /finance/adjustments/{id}/l1-reject — PENDING_L1 → REJECTED.
   * `comment` required (non-empty + non-whitespace).
   */
  rejectAdjustmentL1: (id: number, comment: string): Promise<BillingAdjustment> =>
    asData<BillingAdjustment>(
      request.post<R<BillingAdjustment>>(
        `/api/v1/finance/adjustments/${id}/l1-reject`,
        { comment },
      ),
    ),

  /**
   * POST /finance/adjustments/{id}/l2-approve — PENDING_L2 → L2_APPROVED.
   * Service-layer constraint: operatorId must differ from L1 approver
   * (separation of duties). UI-side guard also applied via
   * `<L2ApprovalButtons />` so users without the perm or the separation
   * never see the button.
   */
  approveAdjustmentL2: (
    id: number,
    comment?: string,
  ): Promise<BillingAdjustment> =>
    asData<BillingAdjustment>(
      request.post<R<BillingAdjustment>>(
        `/api/v1/finance/adjustments/${id}/l2-approve`,
        comment ? { comment } : {},
      ),
    ),

  /** POST /finance/adjustments/{id}/l2-reject — PENDING_L2 → REJECTED. */
  rejectAdjustmentL2: (id: number, comment: string): Promise<BillingAdjustment> =>
    asData<BillingAdjustment>(
      request.post<R<BillingAdjustment>>(
        `/api/v1/finance/adjustments/${id}/l2-reject`,
        { comment },
      ),
    ),

  /**
   * POST /finance/adjustments/{id}/mark-posted — L2_APPROVED → POSTED.
   * Manual confirmation by finance manager.
   */
  markAdjustmentPosted: (id: number): Promise<BillingAdjustment> =>
    asData<BillingAdjustment>(
      request.post<R<BillingAdjustment>>(
        `/api/v1/finance/adjustments/${id}/mark-posted`,
        {},
      ),
    ),
};