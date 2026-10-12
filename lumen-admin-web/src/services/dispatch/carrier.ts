/**
 * Dispatch Carrier service (Iteration 4.1 PR3 / PR6 finalization).
 *
 * Wraps `CarrierController` at `/api/v1/wms/carriers/*`.
 * 承运人不参与状态机流转 — 仅暴露 CRUD + status 字段；与司机同
 * 模式 (ACTIVE / SUSPENDED) 但 controller 没有 suspend / resume 端点.
 *
 * See `lumen-parent/lumen-dispatch/.../controller/CarrierController.java`.
 */

import { request } from '@/services/request';
import type { ApiEnvelope, PageResult } from '@/services/request';

import { asData } from './_util';

/** Local alias for backend `R<T>` envelope — see services/wms/inbound.ts. */
type R<T> = ApiEnvelope<T>;

// ---------------------------------------------------------------------------
// VO type — mirrors backend `WmsCarrier` entity.
// ---------------------------------------------------------------------------

export interface WmsCarrier {
  id: number;
  code: string;
  name: string;
  contact?: string;
  status?: string;          // ACTIVE / SUSPENDED
  createdAt?: string;
  updatedAt?: string;
}

// ---------------------------------------------------------------------------
// Request DTOs
// ---------------------------------------------------------------------------

/** Mirrors `CreateCarrierRequest`. */
export interface CreateCarrierRequest {
  code: string;
  name: string;
  contact?: string;
  status?: string;
}

/** Mirrors `UpdateCarrierRequest` — partial. */
export interface UpdateCarrierRequest {
  code?: string;
  name?: string;
  contact?: string;
  status?: string;
}

/** Query params for `page`. */
export interface ListCarriersParams {
  current?: number;
  size?: number;
  status?: string;
  code?: string;
  name?: string;
}

// ---------------------------------------------------------------------------
// Service namespace
// ---------------------------------------------------------------------------

export const carrierService = {
  /** GET /wms/carriers — paged list. */
  list: (params: ListCarriersParams = {}): Promise<PageResult<WmsCarrier>> =>
    asData<PageResult<WmsCarrier>>(
      request.get<R<PageResult<WmsCarrier>>>(
        '/api/v1/wms/carriers',
        { params },
      ),
    ),

  /** GET /wms/carriers/{id} — detail. */
  get: (id: number): Promise<WmsCarrier> =>
    asData<WmsCarrier>(
      request.get<R<WmsCarrier>>(`/api/v1/wms/carriers/${id}`),
    ),

  /** POST /wms/carriers — create. */
  create: (data: CreateCarrierRequest): Promise<WmsCarrier> =>
    asData<WmsCarrier>(
      request.post<R<WmsCarrier>>('/api/v1/wms/carriers', data),
    ),

  /** PUT /wms/carriers/{id} — partial update. */
  update: (id: number, data: UpdateCarrierRequest): Promise<WmsCarrier> =>
    asData<WmsCarrier>(
      request.put<R<WmsCarrier>>(`/api/v1/wms/carriers/${id}`, data),
    ),

  /** DELETE /wms/carriers/{id} — soft delete. */
  delete: (id: number): Promise<void> =>
    asData<void>(
      request.delete<R<void>>(`/api/v1/wms/carriers/${id}`),
    ),
};
