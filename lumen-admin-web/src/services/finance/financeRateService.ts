/**
 * Lumen Admin — Finance exchange-rate service (Iteration 4.3.2 PR5).
 *
 * Wraps the backend `BillingRateController` at
 * `/api/v1/finance/rates/*`. 3 endpoints (Iteration 4.3.2 PR2-T2.5):
 *
 *   1. `listRates()`     — GET   /api/v1/finance/rates
 *                         (paged; optional filters from/to/fromDate/toDate)
 *   2. `refreshRate()`   — POST  /api/v1/finance/rates/refresh
 *                         (trigger remote USD→CNY sync, returns latest quote)
 *   3. `saveManualRate()` — POST /api/v1/finance/rates/manual
 *                         (admin/finance operator manual entry)
 *
 * Per-action perms (V27 RBAC seed):
 *   `finance:rate:view` — list + refresh
 *   `finance:rate:edit` — manual
 *
 * See `lumen-parent/lumen-finance/.../controller/BillingRateController.java`.
 */

import { request } from '@/services/request';
import type { ApiEnvelope } from '@/services/request';

import type { BillingExchangeRate, RateQuote } from '@/types/finance';

import { asData } from './_util';

/** Local alias for backend `R<T>` envelope — mirrors sibling CS modules. */
type R<T> = ApiEnvelope<T>;

// ---------------------------------------------------------------------------
// Query / request types
// ---------------------------------------------------------------------------

export interface ListFinanceRatesParams {
  from?: string;
  to?: string;
  /** ISO date string (YYYY-MM-DD). */
  fromDate?: string;
  /** ISO date string (YYYY-MM-DD). */
  toDate?: string;
  page?: number;
  size?: number;
}

export interface SaveManualRateRequest {
  from: string;
  to: string;
  rate: number;
  /** ISO date string (YYYY-MM-DD). */
  rateDate: string;
}

// ---------------------------------------------------------------------------
// Service namespace
// ---------------------------------------------------------------------------

export const financeRateService = {
  /**
   * GET /finance/rates — paged list (filters: from / to / fromDate / toDate).
   *
   * Backend returns a flat `List<BillingExchangeRate>`; total count is NOT
   * in the wire shape. Wrap into `{ records, total }` so ProTable adapter
   * can map it consistently with sibling invoice/rule services.
   */
  listRates: async (
    params: ListFinanceRatesParams,
  ): Promise<{ records: BillingExchangeRate[]; total: number }> => {
    const data = await asData<BillingExchangeRate[]>(
      request.get<R<BillingExchangeRate[]>>('/api/v1/finance/rates', {
        params,
      }),
    );
    return { records: data, total: data.length };
  },

  /**
   * POST /finance/rates/refresh — trigger remote sync (USD→CNY).
   *
   * Returns the latest quote (`RateQuote` with rate / rateDate / source).
   */
  refreshRate: (): Promise<RateQuote> =>
    asData<RateQuote>(
      request.post<R<RateQuote>>('/api/v1/finance/rates/refresh', {}),
    ),

  /**
   * POST /finance/rates/manual — manual entry (admin/finance operator).
   *
   * Body params are sent as query params per backend contract.
   */
  saveManualRate: (data: SaveManualRateRequest): Promise<void> =>
    asData<void>(
      request.post<R<void>>('/api/v1/finance/rates/manual', null, {
        params: data,
      }),
    ),
};