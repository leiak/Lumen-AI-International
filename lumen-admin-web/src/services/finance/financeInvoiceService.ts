/**
 * Lumen Admin — Finance invoice service (Iteration 4.3.1 PR4).
 *
 * Wraps the backend `BillingInvoiceController` at
 * `/api/v1/finance/invoices/*`. 5 endpoints (Iteration 4.3.1 PR3):
 *
 *   1. `list()`        — GET   /api/v1/finance/invoices
 *                        (paged — server slices by page/size; response
 *                         is a flat array, not PageResult. Caller should
 *                         use `arr.length` as the page total.)
 *   2. `get()`         — GET   /api/v1/finance/invoices/{id}
 *   3. `submit()`      — POST  /api/v1/finance/invoices/{id}/submit
 *                        (DRAFT → PENDING_L1)
 *   4. `l1Approve()`   — POST  /api/v1/finance/invoices/{id}/l1-approve
 *                        (PENDING_L1 → L1_APPROVED)
 *   5. `l1Reject()`    — POST  /api/v1/finance/invoices/{id}/l1-reject
 *                        (PENDING_L1 → REJECTED; `comment` required)
 *
 * L1 reject wire-shape: backend reads `comment` from the body map, so
 * we send `{ comment }` even though the FE-facing parameter is called
 * `reason` for readability at call sites.
 *
 * Status enum: DRAFT / PENDING_L1 / L1_APPROVED / PUSHED / REJECTED.
 *
 * See `lumen-parent/lumen-finance/.../controller/BillingInvoiceController.java`.
 */

import { request } from '@/services/request';
import type { ApiEnvelope } from '@/services/request';

import { asData } from './_util';

/** Local alias for backend `R<T>` envelope — mirrors sibling CS modules. */
type R<T> = ApiEnvelope<T>;

// ---------------------------------------------------------------------------
// Entity / VO types — mirror backend `BillingInvoiceDtos.View`.
// ---------------------------------------------------------------------------

export type InvoiceStatus =
  | 'DRAFT'
  | 'PENDING_L1'
  | 'L1_APPROVED'
  | 'PUSHED'
  | 'REJECTED';

export type InvoiceDirection = 'RECEIVABLE' | 'PAYABLE';

export interface BillingInvoice {
  id: number;
  tenantId?: number;
  invoiceNo: string;
  direction: InvoiceDirection;
  counterpartyId?: number;
  counterpartyType?: string;
  businessType?: string;
  sourceAccrualId?: number;
  grossAmount?: number;
  taxAmount?: number;
  netAmount?: number;
  currency?: string;
  status: InvoiceStatus;
  ruleId?: number;
  ruleVersion?: number;
  submittedAt?: string;
  l1ApproverId?: number;
  l1ApprovedAt?: string;
  l1Comment?: string;
  l2ApproverId?: number | null;
  l2ApprovedAt?: string | null;
  l2Comment?: string | null;
  errorMsg?: string;
  createdAt?: string;
}

/** Query params for `list`. `pageNum` / `pageSize` mirror the BE params. */
export interface ListFinanceInvoicesParams {
  direction?: InvoiceDirection | string;
  status?: InvoiceStatus | string;
  counterpartyId?: number;
  pageNum: number;
  pageSize: number;
}

// ---------------------------------------------------------------------------
// Service namespace
// ---------------------------------------------------------------------------

export const financeInvoiceService = {
  /**
   * GET /finance/invoices — paged list (filters: direction / status / counterpartyId).
   *
   * Backend returns a flat `List<View>`; total count is NOT in the wire
   * shape. We wrap into a `{ records, total }` so the ProTable adapter
   * can map it consistently with other modules.
   */
  list: async (
    params: ListFinanceInvoicesParams,
  ): Promise<{ records: BillingInvoice[]; total: number }> => {
    const data = await asData<BillingInvoice[]>(
      request.get<R<BillingInvoice[]>>('/api/v1/finance/invoices', {
        params,
      }),
    );
    return { records: data, total: data.length };
  },

  /** GET /finance/invoices/{id} — detail. */
  get: (id: number): Promise<BillingInvoice> =>
    asData<BillingInvoice>(
      request.get<R<BillingInvoice>>(`/api/v1/finance/invoices/${id}`),
    ),

  /**
   * POST /finance/invoices/{id}/submit — DRAFT → PENDING_L1.
   *
   * No payload required; backend reads tenantId/operatorId from the
   * security context. Body is `{}` for symmetry with sibling verbs.
   */
  submit: (id: number): Promise<BillingInvoice> =>
    asData<BillingInvoice>(
      request.post<R<BillingInvoice>>(
        `/api/v1/finance/invoices/${id}/submit`,
        {},
      ),
    ),

  /**
   * POST /finance/invoices/{id}/l1-approve — PENDING_L1 → L1_APPROVED.
   *
   * `comment` is optional (server treats null as "no comment").
   */
  l1Approve: (id: number, comment?: string): Promise<BillingInvoice> =>
    asData<BillingInvoice>(
      request.post<R<BillingInvoice>>(
        `/api/v1/finance/invoices/${id}/l1-approve`,
        comment ? { comment } : {},
      ),
    ),

  /**
   * POST /finance/invoices/{id}/l1-reject — PENDING_L1 → REJECTED.
   *
   * Backend `InvoiceService.l1Reject` rejects empty/whitespace-only
   * comments with INVOICE_REJECT_REASON_REQUIRED → 400.
   */
  l1Reject: (id: number, reason: string): Promise<BillingInvoice> =>
    asData<BillingInvoice>(
      request.post<R<BillingInvoice>>(
        `/api/v1/finance/invoices/${id}/l1-reject`,
        { comment: reason },
      ),
    ),
};