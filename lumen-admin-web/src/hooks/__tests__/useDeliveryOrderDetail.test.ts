/**
 * useDeliveryOrderDetail — composite hook for the DeliveryOrder Detail page
 * (Iteration 4.1 PR6).
 *
 * Locks the contract the Detail page relies on:
 *
 *   1. Mount with a numeric id triggers `get(id)` AND `listStops(id)`
 *      in the right order; `order` + `stops` populate.
 *   2. `listStops` failure is non-fatal — the order is still surfaced
 *      and `stops` falls back to `[]` (so a Detail page without any
 *      stops yet doesn't end up in an "error" state).
 *   3. `get` failure surfaces the error and clears `order`.
 *   4. `id == null` is a no-op — no fetch fires (avoids a 404 toast
 *      before the route param resolves).
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { act, renderHook } from '@testing-library/react';

import { useDeliveryOrderDetail } from '@/hooks/useDeliveryOrderDetail';
import { deliveryOrderService } from '@/services/dispatch/deliveryOrder';

vi.mock('@/services/dispatch/deliveryOrder', () => ({
  deliveryOrderService: {
    get: vi.fn(),
    listStops: vi.fn(),
  },
}));

describe('useDeliveryOrderDetail', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('fetches order + stops on mount and surfaces both', async () => {
    vi.mocked(deliveryOrderService.get).mockResolvedValueOnce({
      id: 7,
      no: 'DO-001',
      status: 'PENDING_DISPATCH',
      sourceNo: 'OUT-1',
      warehouseId: 1,
    });
    vi.mocked(deliveryOrderService.listStops).mockResolvedValueOnce([
      { id: 11, orderId: 7, stopNo: 1, address: 'A 路 1 号', status: 'PENDING' },
      { id: 12, orderId: 7, stopNo: 2, address: 'B 路 2 号', status: 'PENDING' },
    ]);

    const { result } = renderHook(() => useDeliveryOrderDetail(7));
    await act(async () => {});

    expect(deliveryOrderService.get).toHaveBeenCalledWith(7);
    expect(deliveryOrderService.listStops).toHaveBeenCalledWith(7);
    expect(result.current.order).toMatchObject({ id: 7, no: 'DO-001' });
    expect(result.current.stops).toHaveLength(2);
    expect(result.current.error).toBeNull();
    expect(result.current.loading).toBe(false);
  });

  it('swallows a listStops failure but still surfaces the order', async () => {
    vi.mocked(deliveryOrderService.get).mockResolvedValueOnce({
      id: 9,
      no: 'DO-002',
      status: 'PENDING_DISPATCH',
      sourceNo: 'OUT-2',
      warehouseId: 1,
    });
    vi.mocked(deliveryOrderService.listStops).mockRejectedValueOnce(
      new Error('stops endpoint missing'),
    );

    const { result } = renderHook(() => useDeliveryOrderDetail(9));
    await act(async () => {});

    expect(result.current.order).not.toBeNull();
    expect(result.current.stops).toEqual([]);
    expect(result.current.error).toBeNull();
  });

  it('surfaces the error when the order fetch itself fails', async () => {
    vi.mocked(deliveryOrderService.get).mockRejectedValueOnce(
      new Error('boom'),
    );
    vi.mocked(deliveryOrderService.listStops).mockResolvedValueOnce([]);

    const { result } = renderHook(() => useDeliveryOrderDetail(99));
    await act(async () => {});

    expect(result.current.order).toBeNull();
    expect(result.current.error).toBe('boom');
  });

  it('does nothing when id is null/undefined (avoids 404 toast)', async () => {
    const { result } = renderHook(() => useDeliveryOrderDetail(null));
    await act(async () => {});

    expect(deliveryOrderService.get).not.toHaveBeenCalled();
    expect(deliveryOrderService.listStops).not.toHaveBeenCalled();
    expect(result.current.order).toBeNull();
    expect(result.current.stops).toEqual([]);
  });
});
