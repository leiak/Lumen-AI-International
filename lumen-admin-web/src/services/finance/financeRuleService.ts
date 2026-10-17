/**
 * Lumen Admin — Finance billing-rule service (Iteration 4.3.1 PR4).
 *
 * Wraps the backend `BillingRuleController` at
 * `/api/v1/finance/rules/*`. 6 endpoints (Iteration 4.3.1 PR3):
 *
 *   1. `list()`       — GET   /api/v1/finance/rules
 *   2. `get()`        — GET   /api/v1/finance/rules/{id}
 *   3. `create()`     — POST  /api/v1/finance/rules
 *   4. `update()`     — PUT   /api/v1/finance/rules/{id}
 *   5. `activate()`   — POST  /api/v1/finance/rules/{id}/activate
 *                       (DRAFT/DISABLED → ACTIVE)
 *   6. `disable()`    — POST  /api/v1/finance/rules/{id}/disable
 *                       (ACTIVE → DISABLED)
 *
 * Backend returns `R<List<View>>` from list (flat array, not
 * PageResult). Same convention as `financeInvoiceService.list`.
 *
 * Status enum: DRAFT / ACTIVE / DISABLED / INVALID.
 *
 * See `lumen-parent/lumen-finance/.../controller/BillingRuleController.java`.
 */

import { request } from '@/services/request';
import type { ApiEnvelope } from '@/services/request';

import { asData } from './_util';

/** Local alias for backend `R<T>` envelope — mirrors sibling CS modules. */
type R<T> = ApiEnvelope<T>;

// ---------------------------------------------------------------------------
// Entity / VO types — mirror backend `BillingRuleDtos`.
// ---------------------------------------------------------------------------

export type RuleStatus = 'DRAFT' | 'ACTIVE' | 'DISABLED' | 'INVALID';
export type RuleDirection = 'RECEIVABLE' | 'PAYABLE';
export type RuleBusinessType =
  | 'LAST_MILE'
  | 'WAREHOUSE_IN'
  | 'WAREHOUSE_OUT';
export type RuleCounterpartyType = 'CUSTOMER' | 'CARRIER' | 'WAREHOUSE';

/** Mirrors `BillingRuleDtos.RuleItemView`. */
export interface BillingRuleItem {
  id?: number;
  ruleId?: number;
  sequence?: number;
  itemType?: string;
  expression?: string;
  expressionHash?: string;
  paramJson?: string;
  resultField?: string;
  remark?: string;
}

/** Mirrors `BillingRuleDtos.CreateRuleItem`. */
export interface CreateBillingRuleItem {
  sequence: number;
  itemType: string;
  expression: string;
  paramJson?: string;
  resultField?: string;
  remark?: string;
}

/** Mirrors `BillingRuleDtos.View`. */
export interface BillingRule {
  id: number;
  tenantId?: number;
  businessType: string;
  direction: RuleDirection;
  counterpartyType: string;
  currency?: string;
  priority?: number;
  status: RuleStatus;
  effectiveFrom?: string;
  effectiveTo?: string;
  remark?: string;
  items?: BillingRuleItem[];
}

/** Mirrors `BillingRuleDtos.CreateRequest`. */
export interface BillingRuleCreateRequest {
  businessType: string;
  direction: RuleDirection;
  counterpartyType: string;
  currency?: string;
  priority?: number;
  effectiveFrom: string;
  effectiveTo?: string;
  remark?: string;
  items: CreateBillingRuleItem[];
}

/** Mirrors `BillingRuleDtos.UpdateRequest`. All fields optional. */
export interface BillingRuleUpdateRequest {
  priority?: number;
  effectiveTo?: string;
  remark?: string;
  items?: CreateBillingRuleItem[];
}

/** Query params for `list`. */
export interface ListFinanceRulesParams {
  businessType?: string;
  direction?: RuleDirection | string;
  status?: RuleStatus | string;
  pageNum: number;
  pageSize: number;
}

// ---------------------------------------------------------------------------
// Service namespace
// ---------------------------------------------------------------------------

export const financeRuleService = {
  /** GET /finance/rules — paged list (filters: businessType / direction / status). */
  list: async (
    params: ListFinanceRulesParams,
  ): Promise<{ records: BillingRule[]; total: number }> => {
    const data = await asData<BillingRule[]>(
      request.get<R<BillingRule[]>>('/api/v1/finance/rules', { params }),
    );
    return { records: data, total: data.length };
  },

  /** GET /finance/rules/{id} — detail (with items). */
  get: (id: number): Promise<BillingRule> =>
    asData<BillingRule>(
      request.get<R<BillingRule>>(`/api/v1/finance/rules/${id}`),
    ),

  /** POST /finance/rules — create a new rule (defaults to DRAFT). */
  create: (payload: BillingRuleCreateRequest): Promise<BillingRule> =>
    asData<BillingRule>(
      request.post<R<BillingRule>>('/api/v1/finance/rules', payload),
    ),

  /** PUT /finance/rules/{id} — partial update (only non-null fields override). */
  update: (id: number, payload: BillingRuleUpdateRequest): Promise<BillingRule> =>
    asData<BillingRule>(
      request.put<R<BillingRule>>(`/api/v1/finance/rules/${id}`, payload),
    ),

  /** POST /finance/rules/{id}/activate — DRAFT/DISABLED → ACTIVE. */
  activate: (id: number): Promise<BillingRule> =>
    asData<BillingRule>(
      request.post<R<BillingRule>>(
        `/api/v1/finance/rules/${id}/activate`,
        {},
      ),
    ),

  /** POST /finance/rules/{id}/disable — ACTIVE → DISABLED. */
  disable: (id: number): Promise<BillingRule> =>
    asData<BillingRule>(
      request.post<R<BillingRule>>(`/api/v1/finance/rules/${id}/disable`, {}),
    ),
};