/**
 * WMS Inventory ledger service (PR5-T5.4, Iteration 3.0a).
 *
 * Wraps the read-only `WmsInventoryController` at `/api/v1/wms/inventories`.
 * Per the controller Javadoc: writes (POST/PUT/DELETE) are intentionally
 * NOT exposed — balances are mutated by InboundService / OutboundService /
 * AdjustService as a side-effect of business flows. See WmsInventoryController.
 */

import { request } from '@/services/request';
import type { ApiEnvelope, PageResult } from '@/services/request';

/** Local alias for backend `R<T>` envelope — see services/wms/inbound.ts. */
type R<T> = ApiEnvelope<T>;

// ---------------------------------------------------------------------------
// Entity type — mirror backend WmsInventory.
// `qty` / `lockedQty` are BigDecimal on the backend; the wire JSON emits them
// as number or string depending on Jackson config, so callers coerce via
// `Number(...)` or rely on the column formatter.
// ---------------------------------------------------------------------------

export interface WmsInventory {
  id: number;
  warehouseId: number;
  locationId: number;
  itemId: number;
  qty: number | string;
  lockedQty: number | string;
  lastMovementAt?: string;
  createdAt?: string;
  updatedAt?: string;
}

/** Query params for `page`. */
export interface ListInventoryParams {
  /** Mutually exclusive with `itemId`. */
  warehouseId?: number;
  /** Takes precedence over `warehouseId` when both are present. */
  itemId?: number;
  pageNum?: number;
  pageSize?: number;
}

/** Summary payload — currently a placeholder Map<String,Object> on BE. */
export type InventorySummary = Record<string, unknown>;

// ---------------------------------------------------------------------------
// Service namespace
// ---------------------------------------------------------------------------

export const inventoryService = {
  /**
   * GET /wms/inventories — paged list.
   * Backend REQUIRES at least one of `warehouseId` / `itemId`.
   */
  list: (params: ListInventoryParams) =>
    request.get<R<PageResult<WmsInventory>>>('/api/v1/wms/inventories', {
      params,
    }),

  /**
   * GET /wms/inventories/summary — placeholder aggregate.
   * Backend returns an empty map for now; full SQL aggregation lands later.
   */
  summary: () =>
    request.get<R<InventorySummary>>('/api/v1/wms/inventories/summary'),
};