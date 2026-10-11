/**
 * WMS Location masterdata service (PR5-T5.4, Iteration 3.0a).
 *
 * Wraps `WmsLocationController` at `/api/v1/wms/locations`. Adds one extra
 * read-only endpoint vs. the other masterdata services:
 *   GET /wms/locations/by-warehouse/{warehouseId}  — non-paged list for selects.
 */

import { request } from '@/services/request';
import type { ApiEnvelope, PageResult } from '@/services/request';

/** Local alias for backend `R<T>` envelope — see services/wms/inbound.ts. */
type R<T> = ApiEnvelope<T>;

// ---------------------------------------------------------------------------
// VO type — mirrors backend `WmsLocation` entity.
// ---------------------------------------------------------------------------

export interface WmsLocation {
  id: number;
  warehouseId: number;
  code: string;
  zone?: string;
  capacity?: number | string;
  status?: string;
  createdAt?: string;
  updatedAt?: string;
}

// ---------------------------------------------------------------------------
// Request DTOs
// ---------------------------------------------------------------------------

/** Mirrors `CreateLocationRequest`. */
export interface CreateLocationRequest {
  warehouseId: number;
  code: string;
  zone?: string;
  capacity?: number;
  status?: string;
}

/** Mirrors `UpdateLocationRequest` — partial. */
export interface UpdateLocationRequest {
  warehouseId?: number;
  code?: string;
  zone?: string;
  capacity?: number;
  status?: string;
}

/** Query params for `page`. */
export interface ListLocationsParams {
  pageNum?: number;
  pageSize?: number;
  warehouseId?: number;
  keyword?: string;
  status?: string;
}

// ---------------------------------------------------------------------------
// Service namespace
// ---------------------------------------------------------------------------

export const locationService = {
  /** GET /wms/locations — paged list. */
  list: (params: ListLocationsParams = {}) =>
    request.get<R<PageResult<WmsLocation>>>('/api/v1/wms/locations', { params }),

  /** GET /wms/locations/{id} — detail. */
  get: (id: number) =>
    request.get<R<WmsLocation>>(`/api/v1/wms/locations/${id}`),

  /**
   * GET /wms/locations/by-warehouse/{warehouseId} — non-paged list used by
   * 拣货/上架 selects that need every active location under a warehouse.
   */
  listByWarehouse: (warehouseId: number) =>
    request.get<R<WmsLocation[]>>(
      `/api/v1/wms/locations/by-warehouse/${warehouseId}`,
    ),

  /** POST /wms/locations — create. */
  create: (data: CreateLocationRequest) =>
    request.post<R<WmsLocation>>('/api/v1/wms/locations', data),

  /** PUT /wms/locations/{id} — partial update. */
  update: (id: number, data: UpdateLocationRequest) =>
    request.put<R<WmsLocation>>(`/api/v1/wms/locations/${id}`, data),

  /** DELETE /wms/locations/{id} — soft delete. */
  delete: (id: number) =>
    request.delete<R<void>>(`/api/v1/wms/locations/${id}`),
};