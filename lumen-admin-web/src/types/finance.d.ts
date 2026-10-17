/**
 * Lumen Admin — shared Finance TypeScript types (Iteration 4.3.2 PR5).
 *
 * Mirrors the backend DTO/entity shape for the new endpoints added in
 * Iteration 4.3.2 (PR2-T2.5 rates, PR2-T2.6 tax-rates, PR4-T4.2 adjustments).
 *
 * Service wrappers in `services/finance/` import from this module so the
 * call sites stay strongly typed.
 *
 * The 4.3.1 types (BillingInvoice / BillingRule) stay co-located with their
 * services in `services/finance/financeInvoiceService.ts` and
 * `services/finance/financeRuleService.ts`. We only put the cross-service
 * types here.
 */

// ---------------------------------------------------------------------------
// Exchange rate (Iteration 4.3.2 PR2-T2.5) — mirrors
// `com.lumen.finance.entity.BillingExchangeRate`.
// ---------------------------------------------------------------------------

export interface BillingExchangeRate {
  id: number;
  fromCurrency: string;
  toCurrency: string;
  /** BigDecimal on backend, serialised as JSON number; the FE formats it. */
  rate: number;
  /** ISO date string (YYYY-MM-DD). */
  rateDate: string;
  /** MANUAL / REMOTE_PROVIDER_X etc. */
  source: string;
  createdAt?: string;
}

/** Returned by `POST /finance/rates/refresh`. */
export interface RateQuote {
  rate: number;
  rateDate: string;
  source: string;
}

// ---------------------------------------------------------------------------
// Tax rate (Iteration 4.3.2 PR2-T2.6) — mirrors
// `com.lumen.finance.entity.BillingTaxRate`.
// ---------------------------------------------------------------------------

export interface BillingTaxRate {
  id: number;
  currency: string;
  /** BigDecimal; e.g. 0.13 = 13%. */
  taxRate: number;
  /** ISO date string (YYYY-MM-DD). */
  effectiveFrom: string;
  /** ISO date string (YYYY-MM-DD), null = open-ended. */
  effectiveTo: string | null;
  createdAt?: string;
}

// ---------------------------------------------------------------------------
// Adjustment (Iteration 4.3.2 PR4-T4.2) — mirrors
// `com.lumen.finance.entity.BillingAdjustment`.
// ---------------------------------------------------------------------------

export type AdjustmentStatus =
  | 'DRAFT'
  | 'PENDING_L1'
  | 'PENDING_L2'
  | 'L2_APPROVED'
  | 'POSTED'
  | 'REJECTED';

export type AdjustmentType = 'CREDIT' | 'DEBIT';

export interface BillingAdjustment {
  id: number;
  tenantId?: number;
  adjustmentNo: string;
  sourceInvoiceId: number;
  adjustmentType: AdjustmentType;
  amount: number;
  currency: string;
  fxRateAtCreation: number;
  taxRate: number;
  grossAmount: number;
  taxAmount: number;
  netAmount: number;
  reason: string;
  status: AdjustmentStatus;
  l1ApproverId?: number | null;
  l1ApprovedAt?: string | null;
  l1Comment?: string | null;
  l2ApproverId?: number | null;
  l2ApprovedAt?: string | null;
  l2Comment?: string | null;
  errorMsg?: string | null;
  createdAt?: string;
  updatedAt?: string;
}