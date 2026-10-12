/**
 * useVehicleList — composite hook for the vehicle list page (Iteration 4.1 PR6).
 *
 * Locks the behaviour the List page relies on:
 *
 *   1. Initial mount calls `vehicleService.list(initial)` exactly once
 *      and surfaces records on success.
 *   2. `reloadWith({...})` re-fetches and updates rows; `reload()` re-fetches
 *      with the most recent params (not the initial ones).
 *   3. A thrown error lands in the `error` field, the rows are reset to
 *      `[]`, and loading flips back to false (no hung spinner).
 *
 * We mock `vehicleService.list` directly — `useVehicleList` is a thin
 * wrapper around that one call, so going through the service keeps the
 * test honest about what the hook actually depends on.
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { act, renderHook } from '@testing-library/react';

import { useVehicleList } from '@/hooks/useVehicleList';
import { vehicleService, type WmsVehicle } from '@/services/dispatch/vehicle';
import type { PageResult } from '@/services/request';

vi.mock('@/services/dispatch/vehicle', () => ({
  vehicleService: {
    list: vi.fn(),
  },
}));

function pageResult(records: WmsVehicle[], total: number): PageResult<WmsVehicle> {
  return { records, total, pageNum: 1, pageSize: 20 };
}

describe('useVehicleList', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('fetches the initial page on mount and surfaces records', async () => {
    vi.mocked(vehicleService.list).mockResolvedValueOnce(
      pageResult(
        [
          { id: 1, plateNo: '京A12345' },
          { id: 2, plateNo: '京B67890' },
        ],
        2,
      ),
    );

    const { result } = renderHook(() =>
      useVehicleList({ current: 1, size: 20 }),
    );

    // resolve the initial effect's microtasks
    await act(async () => {});

    expect(vi.mocked(vehicleService.list)).toHaveBeenCalledTimes(1);
    expect(vi.mocked(vehicleService.list).mock.calls[0][0]).toEqual({
      current: 1,
      size: 20,
    });
    expect(result.current.rows).toEqual([
      { id: 1, plateNo: '京A12345' },
      { id: 2, plateNo: '京B67890' },
    ]);
    expect(result.current.loading).toBe(false);
    expect(result.current.error).toBeNull();
  });

  it('reloadWith passes the new params; reload re-fetches with the last params', async () => {
    vi.mocked(vehicleService.list).mockResolvedValue(
      pageResult([], 0),
    );

    const { result } = renderHook(() =>
      useVehicleList({ current: 1, size: 20 }),
    );
    await act(async () => {});

    await act(async () => {
      await result.current.reloadWith({ current: 2, size: 50, status: 'IDLE' });
    });

    expect(vi.mocked(vehicleService.list).mock.calls[1][0]).toEqual({
      current: 2,
      size: 50,
      status: 'IDLE',
    });
    // `params` should reflect the latest applied params.
    expect(result.current.params).toEqual({
      current: 2,
      size: 50,
      status: 'IDLE',
    });

    await act(async () => {
      await result.current.reload();
    });

    expect(vi.mocked(vehicleService.list).mock.calls[2][0]).toEqual({
      current: 2,
      size: 50,
      status: 'IDLE',
    });
  });

  it('surfaces errors in `error` and resets rows', async () => {
    vi.mocked(vehicleService.list)
      .mockResolvedValueOnce(pageResult(
        [{ id: 1, plateNo: '京A12345' }],
        1,
      ))
      .mockRejectedValueOnce(new Error('network down'));

    const { result } = renderHook(() => useVehicleList());
    await act(async () => {});

    expect(result.current.rows).toHaveLength(1);

    await act(async () => {
      await result.current.reload();
    });

    expect(result.current.error).toBe('network down');
    expect(result.current.rows).toEqual([]);
    expect(result.current.loading).toBe(false);
  });
});
