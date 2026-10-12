/**
 * DeliveryOrder service (Iteration 4.1 PR4).
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

type R<T> = ApiEnvelope<T>;

// ---------------------------------------------------------------------------
// Entity / VO types — mirror backend WmsDeliveryOrder + DeliveryOrderVO.
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

export interface WmsDeliveryStop {
  id: number;
  orderId: number;
  stopNo: number;
  address: string;
  contactName?: string;
  contactPhone?: string;
  status: string;
  arrivedAt?: string;
  completedAt?: string;
  remark?: string;
}

export interface AssignRequest {
  vehicleId: number;
  driverId: number;
}
export interface ArriveRequest { stopId: number; }
export interface SignRequest   { stopId: number; signPhotoUrl: string; }
export interface CancelRequest { reason: string; }
export interface RaiseExceptionRequest { type: string; description: string; }

function unwrap<T>(env: R<T> | null | undefined): T | null {
  return env && typeof env === 'object' && 'data' in env ? (env as { data: T }).data : (env as unknown as T);
}

export const deliveryOrderService = {
  async page(params: {
    pageNum?: number;
    pageSize?: number;
    status?: string;
    keyword?: string;
  } = {}): Promise<PageResult<WmsDeliveryOrder>> {
    const env = (await request.get('/dispatch/orders', { params })) as unknown as R<PageResult<WmsDeliveryOrder>>;
    return unwrap(env) ?? { records: [], total: 0, pageNum: 1, pageSize: 20 };
  },

  async get(id: number): Promise<WmsDeliveryOrder> {
    const env = (await request.get(`/dispatch/orders/${id}`)) as unknown as R<WmsDeliveryOrder>;
    const d = unwrap(env);
    if (!d) throw new Error('not found');
    return d;
  },

  async listStops(id: number): Promise<WmsDeliveryStop[]> {
    const env = (await request.get(`/dispatch/orders/${id}/stops`)) as unknown as R<WmsDeliveryStop[]>;
    return unwrap(env) ?? [];
  },

  async create(body: {
    sourceNo: string;
    sourceType?: string;
    warehouseId: number;
    totalWeightKg: number;
    totalVolumeM3: number;
    stopCount: number;
    remark?: string;
  }): Promise<WmsDeliveryOrder> {
    const env = (await request.post('/dispatch/orders', body)) as unknown as R<WmsDeliveryOrder>;
    const d = unwrap(env);
    if (!d) throw new Error('create failed');
    return d;
  },

  async update(id: number, body: { totalWeightKg?: number; totalVolumeM3?: number; stopCount?: number; remark?: string; }): Promise<WmsDeliveryOrder> {
    const env = (await request.put(`/dispatch/orders/${id}`, body)) as unknown as R<WmsDeliveryOrder>;
    const d = unwrap(env);
    if (!d) throw new Error('update failed');
    return d;
  },

  async assign(id: number, body: AssignRequest): Promise<WmsDeliveryOrder> {
    const env = (await request.post(`/dispatch/orders/${id}/assign`, body)) as unknown as R<WmsDeliveryOrder>;
    const d = unwrap(env);
    if (!d) throw new Error('assign failed');
    return d;
  },

  async load(id: number): Promise<WmsDeliveryOrder> {
    const env = (await request.post(`/dispatch/orders/${id}/load`)) as unknown as R<WmsDeliveryOrder>;
    const d = unwrap(env);
    if (!d) throw new Error('load failed');
    return d;
  },

  async depart(id: number): Promise<WmsDeliveryOrder> {
    const env = (await request.post(`/dispatch/orders/${id}/depart`)) as unknown as R<WmsDeliveryOrder>;
    const d = unwrap(env);
    if (!d) throw new Error('depart failed');
    return d;
  },

  async arrive(id: number, body: ArriveRequest): Promise<WmsDeliveryOrder> {
    const env = (await request.post(`/dispatch/orders/${id}/arrive`, body)) as unknown as R<WmsDeliveryOrder>;
    const d = unwrap(env);
    if (!d) throw new Error('arrive failed');
    return d;
  },

  async sign(id: number, body: SignRequest): Promise<WmsDeliveryOrder> {
    const env = (await request.post(`/dispatch/orders/${id}/sign`, body)) as unknown as R<WmsDeliveryOrder>;
    const d = unwrap(env);
    if (!d) throw new Error('sign failed');
    return d;
  },

  async cancel(id: number, body: CancelRequest): Promise<WmsDeliveryOrder> {
    const env = (await request.post(`/dispatch/orders/${id}/cancel`, body)) as unknown as R<WmsDeliveryOrder>;
    const d = unwrap(env);
    if (!d) throw new Error('cancel failed');
    return d;
  },

  async raiseException(id: number, body: RaiseExceptionRequest): Promise<unknown> {
    const env = (await request.post(`/dispatch/orders/${id}/raise-exception`, body)) as unknown as R<unknown>;
    return unwrap(env);
  },

  async autoAssign(id: number): Promise<WmsDeliveryOrder> {
    const env = (await request.post(`/dispatch/orders/${id}/auto-assign`)) as unknown as R<WmsDeliveryOrder>;
    const d = unwrap(env);
    if (!d) throw new Error('auto-assign failed');
    return d;
  },
};
