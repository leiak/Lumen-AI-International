/**
 * useDashboardData — composite hook for the Vehicle Utilization Dashboard
 * (Iteration 4.1 PR6).
 *
 * Locks the contract:
 *   1. Mount issues `fleet(range)` AND `daily(range)` in parallel
 *      via `Promise.all` (so a 4-card + ProTable page renders one state).
 *   2. `reloadWith(newRange)` re-fetches both endpoints with the new
 *      range; `reload()` re-fetches with the last range.
 *   3. Either endpoint failing lands in `error` and clears both
 *      `fleet` + `rows`; loading is flipped back to false so the
 *      page doesn't spin forever on a partial outage.
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { act, renderHook } from '@testing-library/react';

import { useDashboardData } from '@/hooks/useDashboardData';
import { utilizationService } from '@/services/dispatch/utilization';

vi.mock('@/services/dispatch/utilization', () => ({
  utilizationService: {
    fleet: vi.fn(),
    daily: vi.fn(),
  },
}));

const RANGE = { dateStart: '2026-10-01', dateEnd: '2026-10-07' };

describe('useDashboardData', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('mount fires fleet + daily in parallel with the initial range', async () => {
    vi.mocked(utilizationService.fleet).mockResolvedValueOnce({
      dateStart: RANGE.dateStart,
      dateEnd: RANGE.dateEnd,
      totalDispatched: 10,
      totalWeightKg: 1000,
      totalVolumeM3: 5,
      averageUtilizationRate: 0.7,
      dailyBreakdown: [],
    });
    vi.mocked(utilizationService.daily).mockResolvedValueOnce([
      { date: '2026-10-01', vehicleId: 1, plateNo: '京A1', dispatchedCount: 3, totalWeightKg: 100, totalVolumeM3: 1, utilizationRate: 0.5 },
    ]);

    const { result } = renderHook(() => useDashboardData(RANGE));
    await act(async () => {});

    expect(utilizationService.fleet).toHaveBeenCalledTimes(1);
    expect(utilizationService.daily).toHaveBeenCalledTimes(1);
    expect(vi.mocked(utilizationService.fleet).mock.calls[0][0]).toEqual(RANGE);
    expect(vi.mocked(utilizationService.daily).mock.calls[0][0]).toEqual(RANGE);
    expect(result.current.fleet?.totalDispatched).toBe(10);
    expect(result.current.rows).toHaveLength(1);
    expect(result.current.loading).toBe(false);
    expect(result.current.error).toBeNull();
  });

  it('reloadWith re-fetches both with the new range; reload re-uses the last', async () => {
    vi.mocked(utilizationService.fleet).mockResolvedValue({
      dateStart: RANGE.dateStart,
      dateEnd: RANGE.dateEnd,
      totalDispatched: 0,
      totalWeightKg: 0,
      totalVolumeM3: 0,
      averageUtilizationRate: 0,
      dailyBreakdown: [],
    });
    vi.mocked(utilizationService.daily).mockResolvedValue([]);

    const { result } = renderHook(() => useDashboardData(RANGE));
    await act(async () => {});

    const NEW = { dateStart: '2026-09-01', dateEnd: '2026-09-30' };
    await act(async () => {
      await result.current.reloadWith(NEW);
    });

    expect(vi.mocked(utilizationService.fleet).mock.calls[1][0]).toEqual(NEW);
    expect(vi.mocked(utilizationService.daily).mock.calls[1][0]).toEqual(NEW);
    expect(result.current.range).toEqual(NEW);

    await act(async () => {
      await result.current.reload();
    });

    expect(vi.mocked(utilizationService.fleet).mock.calls[2][0]).toEqual(NEW);
  });

  it('captures errors and clears both fleet + rows', async () => {
    vi.mocked(utilizationService.fleet)
      .mockResolvedValueOnce({
        dateStart: RANGE.dateStart,
        dateEnd: RANGE.dateEnd,
        totalDispatched: 1,
        totalWeightKg: 1,
        totalVolumeM3: 1,
        averageUtilizationRate: 0.1,
        dailyBreakdown: [],
      });
    vi.mocked(utilizationService.daily)
      .mockResolvedValueOnce([])
      .mockRejectedValueOnce(new Error('daily down'));

    const { result } = renderHook(() => useDashboardData(RANGE));
    await act(async () => {});

    await act(async () => {
      await result.current.reload();
    });

    expect(result.current.error).toBe('daily down');
    expect(result.current.fleet).toBeNull();
    expect(result.current.rows).toEqual([]);
    expect(result.current.loading).toBe(false);
  });
});
