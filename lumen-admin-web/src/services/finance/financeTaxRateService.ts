/**
 * Lumen Admin — Finance tax-rate service (Iteration 4.3.2 PR5).
 *
 * Wraps the backend `BillingTaxRateController` at
 * `/api/v1/finance/tax-rates/*`. 4 endpoints (Iteration 4.3.2 PR2-T2.6):
 *
 *   1. `listTaxRates()`         — GET   /api/v1/finance/tax-rates
 *                                 (paged; optional currency filter)
 *   2. `getActiveTaxRate()`     — GET   /api/v1/finance/tax-rates/active
 *                                 (currency + optional asOf → BigDecimal rate)
 *   3. `createTaxRate()`        — POST  /api/v1/finance/tax-rates
 *   4. `invalidateTaxRate()`    — POST  /api/v1/finance/tax-rates/{id}/invalidate
 *                                 (sets effective_to on a historical row)
 *
 * Per-action perms (V27 RBAC seed):
 *   `finance:taxrate:view` — list + active
 *   `finance:taxrate:edit` — create + invalidate
 *
 * See `lumen-parent/lumen-finance/.../controller/BillingTaxRateController.java`.
 */

import { request } from '@/services/request';
import type { ApiEnvelope } from '@/services/request';

import type { BillingTaxRate } from '@/types/finance';

import { asData } from './_util';

/** Local alias for backend `R<T>` envelope — mirrors sibling CS modules. */
type R<T> = ApiEnvelope<T>;

// ---------------------------------------------------------------------------
// Query / request types
// ---------------------------------------------------------------------------

export interface ListFinanceTaxRatesParams {
  currency?: string;
  page?: number;
  size?: number;
}

export interface GetActiveTaxRateParams {
  currency: string;
  /** ISO date string (YYYY-MM-DD). Backend defaults to today when omitted. */
  asOf?: string;
}

export interface CreateTaxRateRequest {
  currency: string;
  taxRate: number;
  /** ISO date string (YYYY-MM-DD). */
  effectiveFrom: string;
  /** ISO date string (YYYY-MM-DD), optional = long-lived. */
  effectiveTo?: string;
}

// ---------------------------------------------------------------------------
// Service namespace
// ---------------------------------------------------------------------------

export const financeTaxRateService = {
  /**
   * GET /finance/tax-rates — paged list (filter: currency).
   *
   * Backend returns a flat `List<BillingTaxRate>`; total count is NOT in
   * the wire shape. Wrap into `{ records, total }` so ProTable adapter
   * can map it consistently.
   */
  listTaxRates: async (
    params: ListFinanceTaxRatesParams,
  ): Promise<{ records: BillingTaxRate[]; total: number }> => {
    const data = await asData<BillingTaxRate[]>(
      request.get<R<BillingTaxRate[]>>('/api/v1/finance/tax-rates', {
        params,
      }),
    );
    return { records: data, total: data.length };
  },

  /**
   * GET /finance/tax-rates/active — return the active rate (BigDecimal).
   *
   * Returns a number (rate as decimal — e.g. 0.13 for 13%).
   */
  getActiveTaxRate: (params: GetActiveTaxRateParams): Promise<number> =>
    asData<number>(
      request.get<R<number>>('/api/v1/finance/tax-rates/active', { params }),
    ),

  /**
   * POST /finance/tax-rates — create a new tax rate row.
   *
   * Body params are sent as query params per backend contract.
   */
  createTaxRate: (data: CreateTaxRateRequest): Promise<BillingTaxRate> =>
    asData<BillingTaxRate>(
      request.post<R<BillingTaxRate>>('/api/v1/finance/tax-rates', null, {
        params: data,
      }),
    ),

  /**
   * POST /finance/tax-rates/{id}/invalidate — set effective_to on history.
   *
   * `effectiveTo` is sent as a query param per backend contract.
   */
  invalidateTaxRate: (id: number, effectiveTo: string): Promise<void> =>
    asData<void>(
      request.post<R<void>>(
        `/api/v1/finance/tax-rates/${id}/invalidate`,
        null,
        { params: { effectiveTo } },
      ),
    ),
};