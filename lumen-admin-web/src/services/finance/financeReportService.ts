/**
 * Lumen Admin — Finance report export service (Iteration 4.4 PR3).
 *
 * Wraps the backend `BillingReportController` at
 * `/api/v1/finance/reports/*`. 3 GET endpoints (Iteration 4.4
 * PR2-extension, Task 2.4):
 *
 *   1. `summary()`  — GET /api/v1/finance/reports/summary
 *                     (Excel — period invoice / accrual / adjustment totals)
 *   2. `detail()`   — GET /api/v1/finance/reports/detail
 *                     (Excel — period L2_APPROVED invoice line items)
 *   3. `variance()` — GET /api/v1/finance/reports/variance
 *                     (Excel — period reconciliation rows)
 *
 * All three endpoints share the same query contract:
 *   `period`   (required, YYYY-MM)
 *   `currency` (required, 3-char ISO 4217)
 *
 * Perm: `finance:report:view` covers all 3 endpoints (controller renamed
 * from `:export` to `:view` in 4.4 PR2-extension to match the FE-facing
 * "view report" semantic).
 *
 * Wire shape: backend returns `ResponseEntity<byte[]>` with content-type
 * `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet` —
 * NOT wrapped in `R<T>`. The axios response interceptor passes the raw
 * response body through (it only unwraps when the body has a `code`
 * field), so we request `responseType: 'blob'` and return the Blob.
 *
 * Callers typically wrap the Blob in a download link:
 *
 * ```ts
 * const blob = await financeReportService.summary({ period: '2026-10', currency: 'CNY' });
 * const url = URL.createObjectURL(blob);
 * window.open(url);
 * ```
 *
 * See `lumen-parent/lumen-finance/.../controller/BillingReportController.java`.
 */

import { request } from '@/services/request';

import { asData } from './_util';

// ---------------------------------------------------------------------------
// Query / request types
// ---------------------------------------------------------------------------

export type ReportType = 'SUMMARY' | 'DETAIL' | 'VARIANCE';

export interface FinanceReportParams {
  /** `YYYY-MM`. */
  period: string;
  /** 3-char ISO 4217 currency. */
  currency: string;
}

// ---------------------------------------------------------------------------
// Service namespace
// ---------------------------------------------------------------------------

export const financeReportService = {
  /**
   * GET /finance/reports/summary — Excel download of period totals
   * (invoice / accrual / adjustment grand totals).
   */
  summary: async (params: FinanceReportParams): Promise<Blob> =>
    asData<Blob>(
      request.get<Blob>('/api/v1/finance/reports/summary', {
        params,
        responseType: 'blob',
      }),
    ),

  /**
   * GET /finance/reports/detail — Excel download of L2_APPROVED invoice
   * line items for the period.
   */
  detail: async (params: FinanceReportParams): Promise<Blob> =>
    asData<Blob>(
      request.get<Blob>('/api/v1/finance/reports/detail', {
        params,
        responseType: 'blob',
      }),
    ),

  /**
   * GET /finance/reports/variance — Excel download of period
   * reconciliation rows (variance / status / adjustment id).
   */
  variance: async (params: FinanceReportParams): Promise<Blob> =>
    asData<Blob>(
      request.get<Blob>('/api/v1/finance/reports/variance', {
        params,
        responseType: 'blob',
      }),
    ),
};
