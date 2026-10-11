/**
 * WMS Threshold Config service (PR5-T5.4, Iteration 3.0a).
 *
 * Wraps `ThresholdConfigController` at `/api/v1/wms/threshold`. The backend
 * keeps ONE row per tenant — `get` returns the current config (initialising
 * defaults if absent) and `update` mutates it.
 */

import { request } from '@/services/request';
import type { ApiEnvelope } from '@/services/request';

/** Local alias for backend `R<T>` envelope — see services/wms/inbound.ts. */
type R<T> = ApiEnvelope<T>;

// ---------------------------------------------------------------------------
// VO type — mirrors backend `WmsThresholdConfig` entity (== `ThresholdConfigVO`).
// ---------------------------------------------------------------------------

export interface WmsThresholdConfig {
  id: number;
  /** Per-tenant inb/adj approval amount ceiling (BigDecimal on BE). */
  inbAmountThreshold: number | string;
  inbQtyThreshold: number | string;
  outAmountThreshold: number | string;
  outQtyThreshold: number | string;
  adjAmountThreshold: number | string;
  adjQtyThreshold: number | string;
  /** 1 = approval required when exceeded, 0 = gate disabled. */
  approvalRequiredWhenExceed: number;
  createdAt?: string;
  updatedAt?: string;
}

// ---------------------------------------------------------------------------
// Request DTO — mirrors `UpdateThresholdConfigRequest` (no id/tenant — BE
// derives tenant from JWT context).
// ---------------------------------------------------------------------------

export interface UpdateThresholdConfigRequest {
  inbAmountThreshold?: number;
  inbQtyThreshold?: number;
  outAmountThreshold?: number;
  outQtyThreshold?: number;
  adjAmountThreshold?: number;
  adjQtyThreshold?: number;
  approvalRequiredWhenExceed?: number;
}

// ---------------------------------------------------------------------------
// Service namespace
// ---------------------------------------------------------------------------

export const thresholdConfigService = {
  /** GET /wms/threshold — current tenant config (auto-initialises). */
  get: () =>
    request.get<R<WmsThresholdConfig>>('/api/v1/wms/threshold'),

  /** PUT /wms/threshold — partial update; nulls mean "leave alone". */
  update: (data: UpdateThresholdConfigRequest) =>
    request.put<R<WmsThresholdConfig>>('/api/v1/wms/threshold', data),
};