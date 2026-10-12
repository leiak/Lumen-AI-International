/**
 * Dispatch RoutingEngine client stub (Iteration 4.1 PR6).
 *
 * The FE deliberately does NOT call the backend `RoutingEngine` directly —
 * routing is an INTERNAL decision made by the backend `DispatchEngine`
 * when handling `auto-assign` (or future TSP map calls in Iteration 5
 * EP-D1).  Putting a typed client on the FE would imply the routing
 * algorithm runs here, which it does not.
 *
 * The dispatcher UI therefore calls `deliveryOrderService.autoAssign(id)`
 * and surfaces whatever the server decides; no separate `routingService`
 * is wired.
 *
 * This file exists so that (a) the directory layout is documented and
 * (b) a future caller that mistakenly reaches for a `routingService`
 * gets a loud, typed failure instead of a silent 404.
 *
 * See `lumen-parent/lumen-dispatch/engine/MockRoutingEngine.java`.
 */

export interface RoutingPlan {
  vehicleId: number;
  driverId: number;
  stopSequence: number[];
}

/**
 * Throws — use `deliveryOrderService.autoAssign` instead.
 *
 * Kept as a no-op function (no `request.get(...)` etc.) so the file
 * stays free of side-effects at module init. A `throw` here only fires
 * if a future caller actually invokes it.
 */
export const routingService = {
  plan(_orderId: number): Promise<RoutingPlan> {
    return Promise.reject(
      new Error(
        'routingService.plan() is not exposed on the FE — the backend ' +
          'MockRoutingEngine runs server-side. Call ' +
          '`deliveryOrderService.autoAssign(orderId)` instead.',
      ),
    );
  },
};
