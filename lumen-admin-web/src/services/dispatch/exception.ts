/**
 * DeliveryException (异常工单) service (Iteration 4.1 PR4).
 *
 * Wraps the backend DeliveryExceptionController at `/api/v1/dispatch/exceptions/*`.
 * State machine (V20):
 *   OPEN -> INVESTIGATING -> RESOLVED -> CLOSED
 *   CLOSED -> OPEN   (reopen)
 *
 * Note: spec 明确没有 cancel 端点 (异常工单不存在 CANCELLED 出口),
 * 因此本 service 不提供 cancel 方法.
 *
 * See `lumen-parent/lumen-dispatch/.../controller/DeliveryExceptionController.java`.
 */

import { request } from '@/services/request';
import type { ApiEnvelope, PageResult } from '@/services/request';

type R<T> = ApiEnvelope<T>;

export interface WmsDeliveryException {
  id: number;
  orderId: number;
  type: string;        // REJECTED/OVERTIME/DAMAGE/ADDR_ERR
  status: string;      // OPEN/INVESTIGATING/RESOLVED/CLOSED/CANCELLED
  description?: string;
  handler?: string;
  resolvedAt?: string;
  createdAt?: string;
  updatedAt?: string;
}

export interface InvestigateRequest { handler: string; }
export interface ResolveRequest    { resolutionNotes: string; }
export interface ReopenRequest     { reason: string; }
export interface CreateExceptionRequest { orderId: number; type: string; description: string; }

function unwrap<T>(env: R<T> | null | undefined): T | null {
  return env && typeof env === 'object' && 'data' in env ? (env as { data: T }).data : (env as unknown as T);
}

export const deliveryExceptionService = {
  async page(params: { pageNum?: number; pageSize?: number; status?: string; orderId?: number; } = {}): Promise<PageResult<WmsDeliveryException>> {
    const env = (await request.get('/dispatch/exceptions', { params })) as unknown as R<PageResult<WmsDeliveryException>>;
    return unwrap(env) ?? { records: [], total: 0, pageNum: 1, pageSize: 20 };
  },

  async get(id: number): Promise<WmsDeliveryException> {
    const env = (await request.get(`/dispatch/exceptions/${id}`)) as unknown as R<WmsDeliveryException>;
    const d = unwrap(env);
    if (!d) throw new Error('not found');
    return d;
  },

  async create(body: CreateExceptionRequest): Promise<WmsDeliveryException> {
    const env = (await request.post('/dispatch/exceptions', body)) as unknown as R<WmsDeliveryException>;
    const d = unwrap(env);
    if (!d) throw new Error('create failed');
    return d;
  },

  async investigate(id: number, body: InvestigateRequest): Promise<WmsDeliveryException> {
    const env = (await request.post(`/dispatch/exceptions/${id}/investigate`, body)) as unknown as R<WmsDeliveryException>;
    const d = unwrap(env);
    if (!d) throw new Error('investigate failed');
    return d;
  },

  async resolve(id: number, body: ResolveRequest): Promise<WmsDeliveryException> {
    const env = (await request.post(`/dispatch/exceptions/${id}/resolve`, body)) as unknown as R<WmsDeliveryException>;
    const d = unwrap(env);
    if (!d) throw new Error('resolve failed');
    return d;
  },

  async close(id: number): Promise<WmsDeliveryException> {
    const env = (await request.post(`/dispatch/exceptions/${id}/close`)) as unknown as R<WmsDeliveryException>;
    const d = unwrap(env);
    if (!d) throw new Error('close failed');
    return d;
  },

  async reopen(id: number, body: ReopenRequest): Promise<WmsDeliveryException> {
    const env = (await request.post(`/dispatch/exceptions/${id}/reopen`, body)) as unknown as R<WmsDeliveryException>;
    const d = unwrap(env);
    if (!d) throw new Error('reopen failed');
    return d;
  },
};
