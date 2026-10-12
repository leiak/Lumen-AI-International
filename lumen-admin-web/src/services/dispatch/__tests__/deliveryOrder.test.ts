/**
 * services/dispatch/deliveryOrder.ts — smoke tests (Iteration 4.1 PR6).
 *
 * Locks the four most distinctive endpoints in the DeliveryOrder
 * state-machine + CRUD surface:
 *
 *   1. `create()`       — POST with source + dims (sets PENDING_DISPATCH).
 *   2. `assign()`       — POST to /{id}/assign (manual dispatch).
 *   3. `sign()`         — POST to /{id}/sign (ARRIVED -> SIGNED) with
 *                         stopId + signPhotoUrl body.
 *   4. `cancel()`       — POST to /{id}/cancel with reason body
 *                         (any non-terminal -> CANCELLED).
 *
 * These four are the ones a dispatcher actually clicks from the UI;
 * the remaining verbs (load / depart / arrive / raiseException /
 * autoAssign) share the same path/body pattern and are already
 * covered transitively via the round-trip tests in `InboundFlowIT` /
 * `OutboundFlowIT`.
 */

import { describe, it, expect, vi } from 'vitest';

vi.mock('@/services/request', () => {
  const get = vi.fn();
  const post = vi.fn();
  const put = vi.fn();
  const del = vi.fn();
  const request = { get, post, put, delete: del };
  return {
    request,
    default: request,
    PageResult: class {},
    ApiEnvelope: class {},
    ApiError: class extends Error {},
  };
});

import { request } from '@/services/request';
import { deliveryOrderService } from '@/services/dispatch/deliveryOrder';

describe('deliveryOrderService', () => {
  it('create() POSTs source + dims to /dispatch/orders', async () => {
    vi.mocked(request.post).mockResolvedValueOnce({
      code: 0,
      data: { id: 7, no: 'DO-20261012-0001', status: 'PENDING_DISPATCH' },
    });
    await deliveryOrderService.create({
      sourceNo: 'OUT-20261012-0001',
      sourceType: 'OUTBOUND',
      warehouseId: 1,
      totalWeightKg: 100,
      totalVolumeM3: 1.5,
      stopCount: 3,
    });

    expect(request.post).toHaveBeenCalledWith('/api/v1/dispatch/orders', {
      sourceNo: 'OUT-20261012-0001',
      sourceType: 'OUTBOUND',
      warehouseId: 1,
      totalWeightKg: 100,
      totalVolumeM3: 1.5,
      stopCount: 3,
    });
  });

  it('assign() POSTs {vehicleId, driverId} to /{id}/assign', async () => {
    vi.mocked(request.post).mockResolvedValueOnce({
      code: 0,
      data: { id: 7, status: 'DISPATCHED' },
    });
    await deliveryOrderService.assign(7, { vehicleId: 11, driverId: 22 });

    expect(request.post).toHaveBeenCalledWith(
      '/api/v1/dispatch/orders/7/assign',
      { vehicleId: 11, driverId: 22 },
    );
  });

  it('sign() POSTs {stopId, signPhotoUrl} to /{id}/sign', async () => {
    vi.mocked(request.post).mockResolvedValueOnce({
      code: 0,
      data: { id: 7, status: 'SIGNED', signPhotoUrl: 'https://cdn/x.jpg' },
    });
    await deliveryOrderService.sign(7, {
      stopId: 31,
      signPhotoUrl: 'https://cdn/x.jpg',
    });

    expect(request.post).toHaveBeenCalledWith(
      '/api/v1/dispatch/orders/7/sign',
      { stopId: 31, signPhotoUrl: 'https://cdn/x.jpg' },
    );
  });

  it('cancel() POSTs {reason} to /{id}/cancel', async () => {
    vi.mocked(request.post).mockResolvedValueOnce({
      code: 0,
      data: { id: 7, status: 'CANCELLED' },
    });
    await deliveryOrderService.cancel(7, { reason: '客户拒收' });

    expect(request.post).toHaveBeenCalledWith(
      '/api/v1/dispatch/orders/7/cancel',
      { reason: '客户拒收' },
    );
  });
});
