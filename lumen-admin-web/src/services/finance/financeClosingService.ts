/**
 * Lumen Admin — Finance monthly-closing service (Iteration 4.4 PR3).
 *
 * Wraps the backend `BillingClosingController` at
 * `/api/v1/finance/closing/*`. 5 endpoints (Iteration 4.4 PR1-T1.7):
 *
 *   1. `list()`   — GET   /api/v1/finance/closing
 *                   (paged list of all closing rows, ordered by period desc)
 *   2. `get()`    — GET   /api/v1/finance/closing/{period}
 *                   (returns a snapshot; auto-creates an OPEN row on first read)
 *   3. `start()`  — POST  /api/v1/finance/closing/start
 *                   (OPEN → STARTED; body: `{ period }`)
 *   4. `close()`  — POST  /api/v1/finance/closing/close
 *                   (STARTED → CLOSED; requires all period invoices L2_APPROVED)
 *   5. `reopen()` — POST  /api/v1/finance/closing/reopen
 *                   (CLOSED → OPEN; `reason` ≥ 10 chars required)
 *
 * Perm: `finance:closing:manage` covers all 5 endpoints (month-end close
 * is a high-sensitivity operation reserved for finance managers).
 *
 * State machine: `OPEN → STARTED → CLOSED → OPEN` (reopen only). All
 * guard checks live in `MonthlyClosingService`; violations surface as
 * `FinanceException` → HTTP 400.
 *
 * See `lumen-parent/lumen-finance/.../controller/BillingClosingController.java`.
 */

import { request } from '@/services/request';
import type { ApiEnvelope } from '@/services/request';

import { asData } from './_util';

/** Local alias for backend `R<T>` envelope — mirrors sibling CS modules. */
type R<T> = ApiEnvelope<T>;

// ---------------------------------------------------------------------------
// Entity / VO types — mirror backend `BillingMonthlyClosing` + DTOs.
// ---------------------------------------------------------------------------

export type ClosingStatus = 'OPEN' | 'IN_PROGRESS' | 'CLOSED';

/** Mirrors `com.lumen.finance.entity.BillingMonthlyClosing`. */
export interface BillingMonthlyClosing {
  id: number;
  tenantId?: number;
  /** `YYYY-MM` form. */
  period: string;
  status: ClosingStatus | string;
  /** BigDecimal on backend, serialised as JSON number. */
  snapshotTotalInvoice?: number;
  snapshotTotalAccrual?: number;
  snapshotTotalAdjustment?: number;
  /** Snapshot currency (3-char ISO 4217). */
  snapshotCurrency?: string;
  /** ISO timestamp (UTC) of the most recent snapshot recalculation. */
  snapshotRecalculatedAt?: string;
  closedBy?: number;
  /** ISO timestamp (UTC) of the close event. */
  closedAt?: string;
  /** Free-text reason recorded by the operator on reopen. */
  reopenReason?: string;
  createdAt?: string;
  updatedAt?: string;
}

/** Mirrors `ClosingDtos.StartRequest` / `CloseRequest`. */
export interface ClosingPeriodRequest {
  /** `YYYY-MM`. */
  period: string;
}

/** Mirrors `ClosingDtos.ReopenRequest`. */
export interface ReopenClosingRequest {
  /** `YYYY-MM`. */
  period: string;
  /** Reason for the reopen; backend enforces ≥ 10 chars. */
  reason: string;
}

// ---------------------------------------------------------------------------
// Service namespace
// ---------------------------------------------------------------------------

export const financeClosingService = {
  /**
   * GET /finance/closing — paged list (filters: page / size).
   *
   * Backend returns a flat `List<BillingMonthlyClosing>`; total count is
   * NOT in the wire shape. Wrap into `{ records, total }` so ProTable
   * adapter can map it consistently with sibling services.
   */
  list: async (
    params: { page?: number; size?: number } = {},
  ): Promise<{ records: BillingMonthlyClosing[]; total: number }> => {
    const data = await asData<BillingMonthlyClosing[]>(
      request.get<R<BillingMonthlyClosing[]>>('/api/v1/finance/closing', {
        params,
      }),
    );
    return { records: data, total: data.length };
  },

  /**
   * GET /finance/closing/{period} — snapshot for one period.
   *
   * Backend auto-creates an OPEN row on first read (getOrCreate
   * strategy). Path variable is the `YYYY-MM` string (digits + dash —
   * URL-encoding is unnecessary and inconsistent with sibling services).
   */
  get: (period: string): Promise<BillingMonthlyClosing> =>
    asData<BillingMonthlyClosing>(
      request.get<R<BillingMonthlyClosing>>(
        `/api/v1/finance/closing/${period}`,
      ),
    ),

  /** POST /finance/closing/start — OPEN → STARTED. */
  start: (
    data: ClosingPeriodRequest,
  ): Promise<BillingMonthlyClosing> =>
    asData<BillingMonthlyClosing>(
      request.post<R<BillingMonthlyClosing>>(
        '/api/v1/finance/closing/start',
        data,
      ),
    ),

  /** POST /finance/closing/close — STARTED → CLOSED. */
  close: (
    data: ClosingPeriodRequest,
  ): Promise<BillingMonthlyClosing> =>
    asData<BillingMonthlyClosing>(
      request.post<R<BillingMonthlyClosing>>(
        '/api/v1/finance/closing/close',
        data,
      ),
    ),

  /** POST /finance/closing/reopen — CLOSED → OPEN. Reason ≥ 10 chars. */
  reopen: (data: ReopenClosingRequest): Promise<BillingMonthlyClosing> =>
    asData<BillingMonthlyClosing>(
      request.post<R<BillingMonthlyClosing>>(
        '/api/v1/finance/closing/reopen',
        data,
      ),
    ),
};
