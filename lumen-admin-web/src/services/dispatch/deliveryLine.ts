/**
 * Dispatch DeliveryLine service (Iteration 4.1 PR6 minimal stub).
 *
 * Wraps the future `DeliveryOrderController.listLines` endpoint at
 * `/api/v1/dispatch/orders/{id}/lines` once it ships.
 *
 * As of PR6, `wms_delivery_line` is defined (V19) but is not actively
 * populated by the outbound.shipped subscriber (PR4) — that flow creates
 * a DeliveryOrder + DeliveryStop records only. Lines are planned for
 * Iteration 4.1.1 / 4.1.2 once line-level granularity becomes a UX
 * requirement.
 *
 * Until then this module exposes ONLY the `listByOrderId` stub so the
 * FE does not silently drift from the backend contract. Any new
 * call site that needs lines before the backend lands should import
 * `wmsDeliveryLineService` from this module — once the controller
 * endpoint is added, only this file's body needs to change.
 *
 * See `lumen-parent/lumen-dispatch/.../controller/DeliveryOrderController.java`
 * (currently without /lines).
 */

import { request } from '@/services/request';
import type { ApiEnvelope } from '@/services/request';

import { asData } from './_util';

/** Local alias for backend `R<T>` envelope — see services/wms/inbound.ts. */
type R<T> = ApiEnvelope<T>;

// ---------------------------------------------------------------------------
// VO type — mirrors backend `WmsDeliveryLine` entity (V19).
// ---------------------------------------------------------------------------

export interface WmsDeliveryLine {
  id: number;
  orderId: number;
  itemId: number;
  qty: number;
  weightKg?: number;
  volumeM3?: number;
  createdAt?: string;
  updatedAt?: string;
}

// ---------------------------------------------------------------------------
// Service namespace — stub only.
// ---------------------------------------------------------------------------

export const deliveryLineService = {
  /**
   * GET /dispatch/orders/{orderId}/lines — list line items for one order.
   *
   * Throws until the backend exposes `/lines`. Callers that reach here
   * before PR7 should fall back to `outboundNoteService.get(outboundId)`
   * for line data.
   */
  listByOrderId: (orderId: number): Promise<WmsDeliveryLine[]> =>
    asData<WmsDeliveryLine[]>(
      request.get<R<WmsDeliveryLine[]>>(
        `/api/v1/dispatch/orders/${orderId}/lines`,
      ),
    ).catch((err: unknown) => {
      // Backend does not yet expose /lines; surface a clear hint so the
      // caller can fall back instead of seeing a generic 404 toast.
      const msg = err instanceof Error ? err.message : String(err);
      throw new Error(
        `deliveryLineService.listByOrderId(${orderId}) is not yet wired — ` +
          `use outboundNoteService.get(${orderId}) instead. (${msg})`,
      );
    }),
};
