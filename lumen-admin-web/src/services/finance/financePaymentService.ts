/**
 * Lumen Admin — Finance payment-record service (Iteration 4.4 PR3).
 *
 * Wraps the backend `BillingPaymentRecordController` at
 * `/api/v1/finance/payment-records/*`. 4 endpoints (Iteration 4.4 PR1-T1.7):
 *
 *   1. `list()`      — GET   /api/v1/finance/payment-records
 *                      (filters: period / direction; paged server-side)
 *   2. `get()`       — GET   /api/v1/finance/payment-records/{id}
 *   3. `create()`    — POST  /api/v1/finance/payment-records
 *                      (manual entry, body is `CreateRequest`)
 *   4. `importCsv()` — POST  /api/v1/finance/payment-records/import
 *                      (multipart/form-data: `period` + `file`; returns
 *                      `ImportResult` with totalRows / successCount /
 *                      skippedCount / errors)
 *
 * Per-action perms (V30 RBAC seed, 4.4 PR1-extension):
 *   `finance:payment:view` — list + get
 *   `finance:payment:edit` — create + import
 *
 * Status / enum: direction = RECEIVABLE | PAYABLE; counterpartyType =
 * CUSTOMER | CARRIER | WAREHOUSE; source = MANUAL | CSV.
 *
 * See `lumen-parent/lumen-finance/.../controller/BillingPaymentRecordController.java`.
 */

import { request } from '@/services/request';
import type { ApiEnvelope } from '@/services/request';

import { asData } from './_util';

/** Local alias for backend `R<T>` envelope — mirrors sibling CS modules. */
type R<T> = ApiEnvelope<T>;

// ---------------------------------------------------------------------------
// Entity / VO types — mirror backend `BillingPaymentRecord` + DTOs.
// ---------------------------------------------------------------------------

export type PaymentDirection = 'RECEIVABLE' | 'PAYABLE';
export type PaymentSource = 'MANUAL' | 'CSV';
export type CounterpartyType = 'CUSTOMER' | 'CARRIER' | 'WAREHOUSE';

/** Mirrors `com.lumen.finance.entity.BillingPaymentRecord`. */
export interface BillingPaymentRecord {
  id: number;
  tenantId?: number;
  /** Accounting period in `YYYY-MM` form. */
  period: string;
  direction: PaymentDirection | string;
  counterpartyId: number;
  counterpartyType: CounterpartyType | string;
  /** 3-char ISO 4217 currency code (CNY / USD). */
  currency: string;
  /** BigDecimal on backend, serialised as JSON number. */
  amount: number;
  sourceRef?: string;
  /** ISO date string (YYYY-MM-DD). */
  paymentDate: string;
  source: PaymentSource | string;
  remark?: string;
  createdAt?: string;
  createdBy?: number;
}

/** Mirrors `PaymentRecordDtos.CreateRequest`. */
export interface CreatePaymentRecordRequest {
  /** `YYYY-MM`. */
  period: string;
  direction: PaymentDirection | string;
  counterpartyId: number;
  counterpartyType: CounterpartyType | string;
  /** 3-char ISO 4217 currency. */
  currency: string;
  amount: number;
  /** ISO date string (YYYY-MM-DD). */
  paymentDate: string;
  sourceRef?: string;
  remark?: string;
}

/** Mirrors `PaymentRecordDtos.ImportResult`. */
export interface PaymentRecordImportResult {
  totalRows: number;
  successCount: number;
  skippedCount: number;
  errors?: string[];
}

// ---------------------------------------------------------------------------
// Service namespace
// ---------------------------------------------------------------------------

export const financePaymentService = {
  /**
   * GET /finance/payment-records — paged list (filters: period / direction).
   *
   * Backend returns a flat `List<BillingPaymentRecord>`; total count is NOT
   * in the wire shape. Wrap into `{ records, total }` so ProTable adapter
   * can map it consistently with sibling invoice/rule services.
   */
  list: async (
    params: {
      period?: string;
      direction?: PaymentDirection | string;
      page?: number;
      size?: number;
    },
  ): Promise<{ records: BillingPaymentRecord[]; total: number }> => {
    const data = await asData<BillingPaymentRecord[]>(
      request.get<R<BillingPaymentRecord[]>>(
        '/api/v1/finance/payment-records',
        { params },
      ),
    );
    return { records: data, total: data.length };
  },

  /** GET /finance/payment-records/{id} — detail. */
  get: (id: number): Promise<BillingPaymentRecord> =>
    asData<BillingPaymentRecord>(
      request.get<R<BillingPaymentRecord>>(
        `/api/v1/finance/payment-records/${id}`,
      ),
    ),

  /** POST /finance/payment-records — create (manual entry). Body is JSON. */
  create: (
    data: CreatePaymentRecordRequest,
  ): Promise<BillingPaymentRecord> =>
    asData<BillingPaymentRecord>(
      request.post<R<BillingPaymentRecord>>(
        '/api/v1/finance/payment-records',
        data,
      ),
    ),

  /**
   * POST /finance/payment-records/import — multipart/form-data CSV upload.
   *
   * Backend reads `period` (query param) + `file` (multipart file) and
   * returns an `ImportResult`. FormData is sent as-is so axios picks the
   * correct multipart content-type (with boundary).
   */
  importCsv: (
    period: string,
    file: File,
  ): Promise<PaymentRecordImportResult> => {
    const fd = new FormData();
    fd.append('period', period);
    fd.append('file', file);
    return asData<PaymentRecordImportResult>(
      request.post<R<PaymentRecordImportResult>>(
        '/api/v1/finance/payment-records/import',
        fd,
        { params: { period } },
      ),
    );
  },
};
