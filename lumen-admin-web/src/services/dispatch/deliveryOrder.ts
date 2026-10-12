/**
 * DeliveryOrder service (Iteration 4.1 PR4 / PR6 finalization).
 *
 * Wraps the backend DeliveryOrderController at `/api/v1/dispatch/orders/*`.
 * State machine (V20):
 *   PENDING_DISPATCH -> DISPATCHED -> LOADED -> ENROUTE -> ARRIVED -> SIGNED
 *   PENDING_DISPATCH|DISPATCHED|LOADED|ENROUTE -> CANCELLED
 *   LOADED|ENROUTE|ARRIVED -> EXCEPTION
 *
 * See `lumen-parent/lumen-dispatch/.../controller/DeliveryOrderController.java`.
 */

import { request } from '@/services/request';
import type { ApiEnvelope, PageResult } from '@/services/request';

import { asData } from './_util';
import type { WmsDeliveryStop } from './deliveryStop';

/** Local alias for backend `R<T>` envelope — see services/wms/inbound.ts. */
type R<T> = ApiEnvelope<T>;

// ---------------------------------------------------------------------------
// Entity / VO types — mirror backend WmsDeliveryOrder.
// ---------------------------------------------------------------------------

export interface WmsDeliveryOrder {
  id: number;
  no: string;
  sourceNo: string;
  sourceType?: string;
  status: string;
  warehouseId: number;
  carrierId?: number;
  vehicleId?: number;
  driverId?: number;
  totalWeightKg?: number;
  totalVolumeM3?: number;
  stopCount?: number;
  actualDepartAt?: string;
  arrivedAt?: string;
  deliveredAt?: string;
  signPhotoUrl?: string;
  exceptionId?: number;
  remark?: string;
  createdAt?: string;
  updatedAt?: string;
}

/**
 * Re-exported here (canonical home: `deliveryStop.ts`) so the
 * DeliveryOrder Detail page can import `{ WmsDeliveryOrder, WmsDeliveryStop }`
 * from a single module without a second round-trip. The Detail page
 * already lives in the Order subtree and treats stops as auxiliary
 * data of the parent order.
 */
export type { WmsDeliveryStop } from './deliveryStop';

/** Mirrors `AssignRequest`. */
export interface AssignRequest {
  vehicleId: number;
  driverId: number;
}
/** Mirrors `ArriveRequest`. */
export interface ArriveRequest { stopId: number; }
/** Mirrors `SignRequest`. */
export interface SignRequest   { stopId: number; signPhotoUrl: string; }
/** Mirrors `CancelRequest`. */
export interface CancelRequest { reason: string; }
/** Mirrors `RaiseExceptionRequest`. */
export interface RaiseExceptionRequest { type: string; description: string; }

// ---------------------------------------------------------------------------
// Service namespace
// ---------------------------------------------------------------------------

export const deliveryOrderService = {
  /**
   * GET /dispatch/orders — paged list (filters: status / vehicleId / driverId / warehouseId).
   *
   * Accepts BOTH the legacy FE shape (`pageNum` / `pageSize`) used by
   * pre-PR6 pages AND the canonical Spring shape (`current` / `size`)
   * — when both are provided the canonical pair wins. The backend
   * binds to `current` / `size`, so we always send those names on the
   * wire regardless of what the caller passes.
   */
  page: (
    params: {
      current?: number;
      pageNum?: number;
      size?: number;
      pageSize?: number;
      status?: string;
      vehicleId?: number;
      driverId?: number;
      warehouseId?: number;
      keyword?: string;
    } = {},
  ): Promise<PageResult<WmsDeliveryOrder>> => {
    const wire = {
      current: params.current ?? params.pageNum,
      size: params.size ?? params.pageSize,
      status: params.status,
      vehicleId: params.vehicleId,
      driverId: params.driverId,
      warehouseId: params.warehouseId,
    };
    return asData<PageResult<WmsDeliveryOrder>>(
      request.get<R<PageResult<WmsDeliveryOrder>>>(
        '/api/v1/dispatch/orders',
        { params: wire },
      ),
    );
  },

  /** GET /dispatch/orders/{id} — detail. */
  get: (id: number): Promise<WmsDeliveryOrder> =>
    asData<WmsDeliveryOrder>(
      request.get<R<WmsDeliveryOrder>>(`/api/v1/dispatch/orders/${id}`),
    ),

  /**
   * GET /dispatch/orders/{id}/stops — list stops for one order.
   *
   * Strongly typed via `WmsDeliveryStop` (canonical home:
   * `deliveryStop.ts`). The Detail page treats stops as auxiliary
   * data of the parent order, so this convenience method lives here.
   */
  listStops: (id: number): Promise<WmsDeliveryStop[]> =>
    asData<WmsDeliveryStop[]>(
      request.get<R<WmsDeliveryStop[]>>(
        `/api/v1/dispatch/orders/${id}/stops`,
      ),
    ),

  /** POST /dispatch/orders — create with minimal source + dims. */
  create: (data: {
    sourceNo: string;
    sourceType?: string;
    warehouseId: number;
    totalWeightKg: number;
    totalVolumeM3: number;
    stopCount: number;
    remark?: string;
  }): Promise<WmsDeliveryOrder> =>
    asData<WmsDeliveryOrder>(
      request.post<R<WmsDeliveryOrder>>(
        '/api/v1/dispatch/orders',
        data,
      ),
    ),

  /** PUT /dispatch/orders/{id} — partial update of dims / remark. */
  update: (
    id: number,
    data: {
      totalWeightKg?: number;
      totalVolumeM3?: number;
      stopCount?: number;
      remark?: string;
    },
  ): Promise<WmsDeliveryOrder> =>
    asData<WmsDeliveryOrder>(
      request.put<R<WmsDeliveryOrder>>(
        `/api/v1/dispatch/orders/${id}`,
        data,
      ),
    ),

  // -------- State machine transitions (V20 spec §7.1) --------

  /** POST /dispatch/orders/{id}/assign — manual dispatch (vehicle + driver). */
  assign: (id: number, data: AssignRequest): Promise<WmsDeliveryOrder> =>
    asData<WmsDeliveryOrder>(
      request.post<R<WmsDeliveryOrder>>(
        `/api/v1/dispatch/orders/${id}/assign`,
        data,
      ),
    ),

  /** POST /dispatch/orders/{id}/load — DISPATCHED -> LOADED. */
  load: (id: number): Promise<WmsDeliveryOrder> =>
    asData<WmsDeliveryOrder>(
      request.post<R<WmsDeliveryOrder>>(
        `/api/v1/dispatch/orders/${id}/load`,
        {},
      ),
    ),

  /** POST /dispatch/orders/{id}/depart — LOADED -> ENROUTE. */
  depart: (id: number): Promise<WmsDeliveryOrder> =>
    asData<WmsDeliveryOrder>(
      request.post<R<WmsDeliveryOrder>>(
        `/api/v1/dispatch/orders/${id}/depart`,
        {},
      ),
    ),

  /** POST /dispatch/orders/{id}/arrive — ENROUTE -> ARRIVED (per stop). */
  arrive: (id: number, data: ArriveRequest): Promise<WmsDeliveryOrder> =>
    asData<WmsDeliveryOrder>(
      request.post<R<WmsDeliveryOrder>>(
        `/api/v1/dispatch/orders/${id}/arrive`,
        data,
      ),
    ),

  /** POST /dispatch/orders/{id}/sign — ARRIVED -> SIGNED. */
  sign: (id: number, data: SignRequest): Promise<WmsDeliveryOrder> =>
    asData<WmsDeliveryOrder>(
      request.post<R<WmsDeliveryOrder>>(
        `/api/v1/dispatch/orders/${id}/sign`,
        data,
      ),
    ),

  /** POST /dispatch/orders/{id}/cancel — * -> CANCELLED. */
  cancel: (id: number, data: CancelRequest): Promise<WmsDeliveryOrder> =>
    asData<WmsDeliveryOrder>(
      request.post<R<WmsDeliveryOrder>>(
        `/api/v1/dispatch/orders/${id}/cancel`,
        data,
      ),
    ),

  /** POST /dispatch/orders/{id}/raise-exception — open an exception WO + flip to EXCEPTION. */
  raiseException: (id: number, data: RaiseExceptionRequest): Promise<unknown> =>
    asData<unknown>(
      request.post<R<unknown>>(
        `/api/v1/dispatch/orders/${id}/raise-exception`,
        data,
      ),
    ),

  /** POST /dispatch/orders/auto-assign/{id} — server-side MockRoutingEngine dispatch. */
  autoAssign: (id: number): Promise<WmsDeliveryOrder> =>
    asData<WmsDeliveryOrder>(
      request.post<R<WmsDeliveryOrder>>(
        `/api/v1/dispatch/orders/auto-assign/${id}`,
        {},
      ),
    ),
};
