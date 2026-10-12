/**
 * DeliveryException (异常工单) service (Iteration 4.1 PR4 / PR6 finalization).
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

import { asData } from './_util';

/** Local alias for backend `R<T>` envelope — see services/wms/inbound.ts. */
type R<T> = ApiEnvelope<T>;

// ---------------------------------------------------------------------------
// Entity / VO types — mirror backend `WmsDeliveryException`.
// ---------------------------------------------------------------------------

export interface WmsDeliveryException {
  id: number;
  orderId: number;
  type: string;        // REJECTED / OVERTIME / DAMAGE / ADDR_ERR
  status: string;      // OPEN / INVESTIGATING / RESOLVED / CLOSED / CANCELLED
  description?: string;
  handler?: string;
  resolvedAt?: string;
  createdAt?: string;
  updatedAt?: string;
}

/** Mirrors `InvestigateRequest`. */
export interface InvestigateRequest { handler: string; }
/** Mirrors `ResolveRequest`. */
export interface ResolveRequest    { resolutionNotes: string; }
/** Mirrors `ReopenRequest`. */
export interface ReopenRequest     { reason: string; }
/** Mirrors `CreateExceptionRequest` (direct create; rare — most exceptions come from `order/raise-exception`). */
export interface CreateExceptionRequest {
  orderId: number;
  type: string;
  description: string;
}

// ---------------------------------------------------------------------------
// Service namespace
// ---------------------------------------------------------------------------

export const deliveryExceptionService = {
  /**
   * GET /dispatch/exceptions — paged list (filters: status / orderId).
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
      orderId?: number;
    } = {},
  ): Promise<PageResult<WmsDeliveryException>> => {
    const wire = {
      current: params.current ?? params.pageNum,
      size: params.size ?? params.pageSize,
      status: params.status,
      orderId: params.orderId,
    };
    return asData<PageResult<WmsDeliveryException>>(
      request.get<R<PageResult<WmsDeliveryException>>>(
        '/api/v1/dispatch/exceptions',
        { params: wire },
      ),
    );
  },

  /** GET /dispatch/exceptions/{id} — detail. */
  get: (id: number): Promise<WmsDeliveryException> =>
    asData<WmsDeliveryException>(
      request.get<R<WmsDeliveryException>>(
        `/api/v1/dispatch/exceptions/${id}`,
      ),
    ),

  /** POST /dispatch/exceptions — direct create (rare; prefer raise-exception on the order). */
  create: (data: CreateExceptionRequest): Promise<WmsDeliveryException> =>
    asData<WmsDeliveryException>(
      request.post<R<WmsDeliveryException>>(
        '/api/v1/dispatch/exceptions',
        data,
      ),
    ),

  /** POST /dispatch/exceptions/{id}/investigate — OPEN -> INVESTIGATING. */
  investigate: (
    id: number,
    data: InvestigateRequest,
  ): Promise<WmsDeliveryException> =>
    asData<WmsDeliveryException>(
      request.post<R<WmsDeliveryException>>(
        `/api/v1/dispatch/exceptions/${id}/investigate`,
        data,
      ),
    ),

  /** POST /dispatch/exceptions/{id}/resolve — INVESTIGATING -> RESOLVED. */
  resolve: (id: number, data: ResolveRequest): Promise<WmsDeliveryException> =>
    asData<WmsDeliveryException>(
      request.post<R<WmsDeliveryException>>(
        `/api/v1/dispatch/exceptions/${id}/resolve`,
        data,
      ),
    ),

  /** POST /dispatch/exceptions/{id}/close — RESOLVED -> CLOSED. */
  close: (id: number): Promise<WmsDeliveryException> =>
    asData<WmsDeliveryException>(
      request.post<R<WmsDeliveryException>>(
        `/api/v1/dispatch/exceptions/${id}/close`,
        {},
      ),
    ),

  /** POST /dispatch/exceptions/{id}/reopen — CLOSED -> OPEN. */
  reopen: (id: number, data: ReopenRequest): Promise<WmsDeliveryException> =>
    asData<WmsDeliveryException>(
      request.post<R<WmsDeliveryException>>(
        `/api/v1/dispatch/exceptions/${id}/reopen`,
        data,
      ),
    ),
};
