/**
 * WMS service barrel (PR5-T5.4).
 *
 * Re-exports every WMS service namespace so callers can do
 *   import { inboundNoteService, outboundNoteService } from '@/services/wms';
 *
 * Consumers should prefer the namespaced service objects over hand-rolled
 * `request.get('/wms/...')` calls so the 401-refresh / envelope unwrap
 * behaviour stays consistent across the app.
 */

export * from './inbound';
export * from './outbound';
export * from './adjust';
export * from './inventory';
export * from './threshold';
export * from './warehouse';
export * from './item';
export * from './customer';
export * from './location';