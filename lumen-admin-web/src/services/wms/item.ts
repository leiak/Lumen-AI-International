/**
 * WMS Item masterdata service (PR5-T5.4, Iteration 3.0a).
 *
 * Wraps `WmsItemController` at `/api/v1/wms/items`. Pure CRUD — no state
 * machine on this entity.
 */

import { request } from '@/services/request';
import type { ApiEnvelope, PageResult } from '@/services/request';

/** Local alias for backend `R<T>` envelope — see services/wms/inbound.ts. */
type R<T> = ApiEnvelope<T>;

// ---------------------------------------------------------------------------
// VO type — mirrors backend `WmsItem` entity.
// ---------------------------------------------------------------------------

export interface WmsItem {
  id: number;
  sku: string;
  name: string;
  uom?: string;
  barcode?: string;
  hazardClass?: string;
  unitCost?: number | string;
  createdAt?: string;
  updatedAt?: string;
}

// ---------------------------------------------------------------------------
// Request DTOs
// ---------------------------------------------------------------------------

/** Mirrors `CreateItemRequest`. */
export interface CreateItemRequest {
  sku: string;
  name: string;
  uom?: string;
  barcode?: string;
  hazardClass?: string;
  unitCost?: number;
}

/** Mirrors `UpdateItemRequest` — partial. */
export interface UpdateItemRequest {
  sku?: string;
  name?: string;
  uom?: string;
  barcode?: string;
  hazardClass?: string;
  unitCost?: number;
}

/** Query params for `page`. */
export interface ListItemsParams {
  pageNum?: number;
  pageSize?: number;
  keyword?: string;
}

// ---------------------------------------------------------------------------
// Service namespace
// ---------------------------------------------------------------------------

export const itemService = {
  /** GET /wms/items — paged list (keyword filter). */
  list: (params: ListItemsParams = {}) =>
    request.get<R<PageResult<WmsItem>>>('/api/v1/wms/items', { params }),

  /** GET /wms/items/{id} — detail. */
  get: (id: number) =>
    request.get<R<WmsItem>>(`/api/v1/wms/items/${id}`),

  /** POST /wms/items — create. */
  create: (data: CreateItemRequest) =>
    request.post<R<WmsItem>>('/api/v1/wms/items', data),

  /** PUT /wms/items/{id} — partial update. */
  update: (id: number, data: UpdateItemRequest) =>
    request.put<R<WmsItem>>(`/api/v1/wms/items/${id}`, data),

  /** DELETE /wms/items/{id} — soft delete. */
  delete: (id: number) =>
    request.delete<R<void>>(`/api/v1/wms/items/${id}`),
};