/**
 * Dispatch DeliveryStop service (Iteration 4.1 PR6 minimal stub).
 *
 * Wraps `DeliveryOrderController.listStops` at `/api/v1/dispatch/orders/{id}/stops`.
 * Stops are produced server-side alongside the parent order; the FE never
 * creates / updates / deletes stops directly — the only meaningful verb is
 * `listByOrderId(orderId)`.
 *
 * State machine per stop (V19):
 *   PENDING -> ARRIVED -> COMPLETED
 *   PENDING / ARRIVED -> FAILED
 *
 * See `lumen-parent/lumen-dispatch/.../controller/DeliveryOrderController.java`.
 */

import { request } from '@/services/request';
import type { ApiEnvelope } from '@/services/request';

import { asData } from './_util';

/** Local alias for backend `R<T>` envelope — see services/wms/inbound.ts. */
type R<T> = ApiEnvelope<T>;

// ---------------------------------------------------------------------------
// VO type — mirrors backend `WmsDeliveryStop` entity.
// ---------------------------------------------------------------------------

export interface WmsDeliveryStop {
  id: number;
  orderId: number;
  stopNo: number;
  address: string;
  contactName?: string;
  contactPhone?: string;
  status?: string;          // PENDING / ARRIVED / COMPLETED / FAILED
  arrivedAt?: string;
  completedAt?: string;
  failureReason?: string;
  remark?: string;
  createdAt?: string;
  updatedAt?: string;
}

// ---------------------------------------------------------------------------
// Service namespace — minimal (PR6). Stop mutations happen via the parent
// order's arrive / sign endpoints, not directly on stops.
// ---------------------------------------------------------------------------

export const deliveryStopService = {
  /** GET /dispatch/orders/{orderId}/stops — list all stops for one order. */
  listByOrderId: (orderId: number): Promise<WmsDeliveryStop[]> =>
    asData<WmsDeliveryStop[]>(
      request.get<R<WmsDeliveryStop[]>>(
        `/api/v1/dispatch/orders/${orderId}/stops`,
      ),
    ),
};
