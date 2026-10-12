/**
 * Dispatch Driver service (Iteration 4.1 PR3 / PR6 finalization).
 *
 * Wraps `DriverController` at `/api/v1/wms/drivers/*`.
 * State machine (极简):
 *   ACTIVE <-> SUSPENDED   (driver:freeze = suspend / resume)
 *   No V20 transition entries — service 层直接 setStatus.
 *
 * See `lumen-parent/lumen-dispatch/.../controller/DriverController.java`.
 */

import { request } from '@/services/request';
import type { ApiEnvelope, PageResult } from '@/services/request';

import { asData } from './_util';

/** Local alias for backend `R<T>` envelope — see services/wms/inbound.ts. */
type R<T> = ApiEnvelope<T>;

// ---------------------------------------------------------------------------
// VO type — mirrors backend `WmsDriver` entity.
// ---------------------------------------------------------------------------

export interface WmsDriver {
  id: number;
  name: string;
  phone?: string;
  licenseNo?: string;
  status?: string;          // ACTIVE / SUSPENDED
  remark?: string;
  createdAt?: string;
  updatedAt?: string;
}

// ---------------------------------------------------------------------------
// Request DTOs
// ---------------------------------------------------------------------------

/** Mirrors `CreateDriverRequest`. */
export interface CreateDriverRequest {
  name: string;
  phone: string;
  licenseNo: string;
  status?: string;
  remark?: string;
}

/** Mirrors `UpdateDriverRequest` — partial. */
export interface UpdateDriverRequest {
  name?: string;
  phone?: string;
  licenseNo?: string;
  status?: string;
  remark?: string;
}

/** Query params for `page`. */
export interface ListDriversParams {
  current?: number;
  size?: number;
  status?: string;
  name?: string;
}

// ---------------------------------------------------------------------------
// Service namespace
// ---------------------------------------------------------------------------

export const driverService = {
  /** GET /wms/drivers — paged list. */
  list: (params: ListDriversParams = {}): Promise<PageResult<WmsDriver>> =>
    asData<PageResult<WmsDriver>>(
      request.get<R<PageResult<WmsDriver>>>('/api/v1/wms/drivers', { params }),
    ),

  /** GET /wms/drivers/{id} — detail. */
  get: (id: number): Promise<WmsDriver> =>
    asData<WmsDriver>(
      request.get<R<WmsDriver>>(`/api/v1/wms/drivers/${id}`),
    ),

  /** POST /wms/drivers — create. */
  create: (data: CreateDriverRequest): Promise<WmsDriver> =>
    asData<WmsDriver>(
      request.post<R<WmsDriver>>('/api/v1/wms/drivers', data),
    ),

  /** PUT /wms/drivers/{id} — partial update. */
  update: (id: number, data: UpdateDriverRequest): Promise<WmsDriver> =>
    asData<WmsDriver>(
      request.put<R<WmsDriver>>(`/api/v1/wms/drivers/${id}`, data),
    ),

  /** DELETE /wms/drivers/{id} — soft delete. */
  delete: (id: number): Promise<void> =>
    asData<void>(
      request.delete<R<void>>(`/api/v1/wms/drivers/${id}`),
    ),

  /** POST /wms/drivers/{id}/suspend — ACTIVE -> SUSPENDED. */
  suspend: (id: number): Promise<WmsDriver> =>
    asData<WmsDriver>(
      request.post<R<WmsDriver>>(`/api/v1/wms/drivers/${id}/suspend`, {}),
    ),

  /** POST /wms/drivers/{id}/resume — SUSPENDED -> ACTIVE. */
  resume: (id: number): Promise<WmsDriver> =>
    asData<WmsDriver>(
      request.post<R<WmsDriver>>(`/api/v1/wms/drivers/${id}/resume`, {}),
    ),
};
