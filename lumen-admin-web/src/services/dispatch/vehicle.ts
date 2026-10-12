/**
 * Dispatch Vehicle service (Iteration 4.1 PR3 / PR6 finalization).
 *
 * Wraps `VehicleController` at `/api/v1/wms/vehicles/*`.
 * State machine (V20): IDLE / DISPATCHED / LOADING / ENROUTE / RETURNING / OUT.
 * `freeze` / `unfreeze` collapse any non-terminal status into OUT and back via
 * `dispatch:vehicle:freeze`.
 *
 * See `lumen-parent/lumen-dispatch/.../controller/VehicleController.java`.
 */

import { request } from '@/services/request';
import type { ApiEnvelope, PageResult } from '@/services/request';

import { asData } from './_util';

/** Local alias for backend `R<T>` envelope — see services/wms/inbound.ts. */
type R<T> = ApiEnvelope<T>;

// ---------------------------------------------------------------------------
// VO type — mirrors backend `WmsVehicle` entity.
// ---------------------------------------------------------------------------

export interface WmsVehicle {
  id: number;
  plateNo: string;
  type?: string;            // SMALL / MEDIUM / LARGE
  maxLoadKg?: number;
  maxVolumeM3?: number;
  status?: string;          // IDLE / DISPATCHED / LOADING / ENROUTE / RETURNING / OUT
  driverId?: number;
  carrierId?: number;
  remark?: string;
  createdAt?: string;
  updatedAt?: string;
}

// ---------------------------------------------------------------------------
// Request DTOs
// ---------------------------------------------------------------------------

/** Mirrors `CreateVehicleRequest`. */
export interface CreateVehicleRequest {
  plateNo: string;
  type: string;
  maxLoadKg: number;
  maxVolumeM3: number;
  status?: string;
  driverId?: number;
  carrierId?: number;
  remark?: string;
}

/** Mirrors `UpdateVehicleRequest` — partial. */
export interface UpdateVehicleRequest {
  plateNo?: string;
  type?: string;
  maxLoadKg?: number;
  maxVolumeM3?: number;
  status?: string;
  driverId?: number;
  carrierId?: number;
  remark?: string;
}

/** Query params for `page`. */
export interface ListVehiclesParams {
  current?: number;
  size?: number;
  status?: string;
  type?: string;
  plateNo?: string;
}

// ---------------------------------------------------------------------------
// Service namespace
// ---------------------------------------------------------------------------

export const vehicleService = {
  /** GET /wms/vehicles — paged list. */
  list: (params: ListVehiclesParams = {}): Promise<PageResult<WmsVehicle>> =>
    asData<PageResult<WmsVehicle>>(
      request.get<R<PageResult<WmsVehicle>>>(
        '/api/v1/wms/vehicles',
        { params },
      ),
    ),

  /** GET /wms/vehicles/{id} — detail. */
  get: (id: number): Promise<WmsVehicle> =>
    asData<WmsVehicle>(
      request.get<R<WmsVehicle>>(`/api/v1/wms/vehicles/${id}`),
    ),

  /** POST /wms/vehicles — create. */
  create: (data: CreateVehicleRequest): Promise<WmsVehicle> =>
    asData<WmsVehicle>(
      request.post<R<WmsVehicle>>('/api/v1/wms/vehicles', data),
    ),

  /** PUT /wms/vehicles/{id} — partial update. */
  update: (id: number, data: UpdateVehicleRequest): Promise<WmsVehicle> =>
    asData<WmsVehicle>(
      request.put<R<WmsVehicle>>(`/api/v1/wms/vehicles/${id}`, data),
    ),

  /** DELETE /wms/vehicles/{id} — soft delete. */
  delete: (id: number): Promise<void> =>
    asData<void>(
      request.delete<R<void>>(`/api/v1/wms/vehicles/${id}`),
    ),

  /** POST /wms/vehicles/{id}/freeze — set status = OUT. */
  freeze: (id: number): Promise<WmsVehicle> =>
    asData<WmsVehicle>(
      request.post<R<WmsVehicle>>(
        `/api/v1/wms/vehicles/${id}/freeze`,
        {},
      ),
    ),

  /** POST /wms/vehicles/{id}/unfreeze — restore from OUT. */
  unfreeze: (id: number): Promise<WmsVehicle> =>
    asData<WmsVehicle>(
      request.post<R<WmsVehicle>>(
        `/api/v1/wms/vehicles/${id}/unfreeze`,
        {},
      ),
    ),
};
