/**
 * WMS Warehouse masterdata service (PR5-T5.4, Iteration 3.0a).
 *
 * Wraps `WmsWarehouseController` at `/api/v1/wms/warehouses`.
 * Pure CRUD — freeze/disable state machine is PR3+ per controller Javadoc.
 */

import { request } from '@/services/request';
import type { ApiEnvelope, PageResult } from '@/services/request';

/** Local alias for backend `R<T>` envelope — see services/wms/inbound.ts. */
type R<T> = ApiEnvelope<T>;

// ---------------------------------------------------------------------------
// VO type — mirrors backend `WmsWarehouse` entity.
// ---------------------------------------------------------------------------

export interface WmsWarehouse {
  id: number;
  code: string;
  name: string;
  type?: string;
  status?: string;
  address?: string;
  createdAt?: string;
  updatedAt?: string;
}

// ---------------------------------------------------------------------------
// Request DTOs
// ---------------------------------------------------------------------------

/** Mirrors `CreateWarehouseRequest`. */
export interface CreateWarehouseRequest {
  code: string;
  name: string;
  type?: string;
  status?: string;
  address?: string;
}

/** Mirrors `UpdateWarehouseRequest` — partial. */
export interface UpdateWarehouseRequest {
  code?: string;
  name?: string;
  type?: string;
  status?: string;
  address?: string;
}

/** Query params for `page`. */
export interface ListWarehousesParams {
  pageNum?: number;
  pageSize?: number;
  keyword?: string;
  status?: string;
}

// ---------------------------------------------------------------------------
// Service namespace
// ---------------------------------------------------------------------------

export const warehouseService = {
  /** GET /wms/warehouses — paged list. */
  list: (params: ListWarehousesParams = {}) =>
    request.get<R<PageResult<WmsWarehouse>>>('/api/v1/wms/warehouses', {
      params,
    }),

  /** GET /wms/warehouses/{id} — detail. */
  get: (id: number) =>
    request.get<R<WmsWarehouse>>(`/api/v1/wms/warehouses/${id}`),

  /** POST /wms/warehouses — create. */
  create: (data: CreateWarehouseRequest) =>
    request.post<R<WmsWarehouse>>('/api/v1/wms/warehouses', data),

  /** PUT /wms/warehouses/{id} — partial update. */
  update: (id: number, data: UpdateWarehouseRequest) =>
    request.put<R<WmsWarehouse>>(`/api/v1/wms/warehouses/${id}`, data),

  /** DELETE /wms/warehouses/{id} — soft delete. */
  delete: (id: number) =>
    request.delete<R<void>>(`/api/v1/wms/warehouses/${id}`),
};