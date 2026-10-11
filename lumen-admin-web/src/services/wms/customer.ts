/**
 * WMS Customer masterdata service (PR5-T5.4, Iteration 3.0a).
 *
 * Wraps `WmsCustomerController` at `/api/v1/wms/customers`. Pure CRUD — no
 * status / freeze column on the entity.
 */

import { request } from '@/services/request';
import type { ApiEnvelope, PageResult } from '@/services/request';

/** Local alias for backend `R<T>` envelope — see services/wms/inbound.ts. */
type R<T> = ApiEnvelope<T>;

// ---------------------------------------------------------------------------
// VO type — mirrors backend `WmsCustomer` entity.
// ---------------------------------------------------------------------------

export interface WmsCustomer {
  id: number;
  code: string;
  name: string;
  contact?: string;
  phone?: string;
  address?: string;
  createdAt?: string;
  updatedAt?: string;
}

// ---------------------------------------------------------------------------
// Request DTOs
// ---------------------------------------------------------------------------

/** Mirrors `CreateCustomerRequest`. */
export interface CreateCustomerRequest {
  code: string;
  name: string;
  contact?: string;
  phone?: string;
  address?: string;
}

/** Mirrors `UpdateCustomerRequest` — partial. */
export interface UpdateCustomerRequest {
  code?: string;
  name?: string;
  contact?: string;
  phone?: string;
  address?: string;
}

/** Query params for `page`. */
export interface ListCustomersParams {
  pageNum?: number;
  pageSize?: number;
  keyword?: string;
}

// ---------------------------------------------------------------------------
// Service namespace
// ---------------------------------------------------------------------------

export const customerService = {
  /** GET /wms/customers — paged list. */
  list: (params: ListCustomersParams = {}) =>
    request.get<R<PageResult<WmsCustomer>>>('/api/v1/wms/customers', { params }),

  /** GET /wms/customers/{id} — detail. */
  get: (id: number) =>
    request.get<R<WmsCustomer>>(`/api/v1/wms/customers/${id}`),

  /** POST /wms/customers — create. */
  create: (data: CreateCustomerRequest) =>
    request.post<R<WmsCustomer>>('/api/v1/wms/customers', data),

  /** PUT /wms/customers/{id} — partial update. */
  update: (id: number, data: UpdateCustomerRequest) =>
    request.put<R<WmsCustomer>>(`/api/v1/wms/customers/${id}`, data),

  /** DELETE /wms/customers/{id} — soft delete. */
  delete: (id: number) =>
    request.delete<R<void>>(`/api/v1/wms/customers/${id}`),
};