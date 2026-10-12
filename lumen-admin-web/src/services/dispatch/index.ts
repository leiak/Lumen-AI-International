/**
 * Dispatch service barrel (Iteration 4.1 PR6).
 *
 * Re-exports every typed dispatch service namespace so callers can do
 *   import { vehicleService, deliveryOrderService } from '@/services/dispatch';
 *
 * Consumers should prefer the namespaced service objects over hand-rolled
 * `request.get('/wms/...')` calls so the 401-refresh / envelope unwrap
 * behaviour stays consistent across the app.
 *
 * Routing is intentionally NOT exported — see routing.ts for the rationale
 * (the FE calls `deliveryOrderService.autoAssign`, not a routing endpoint).
 */

export * from './vehicle';
export * from './driver';
export * from './carrier';
export * from './deliveryOrder';
export * from './deliveryStop';
export * from './deliveryLine';
export * from './exception';
export * from './utilization';
