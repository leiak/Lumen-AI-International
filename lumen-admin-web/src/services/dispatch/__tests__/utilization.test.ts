/**
 * services/dispatch/utilization.ts — smoke (Iteration 4.1 PR6).
 *
 * One smoke test for the daily endpoint — the surface a dispatcher
 * actually calls when changing the date range on the dashboard.
 *
 * `fleet()` shares the verb/path shape with `daily()` (GET, query
 * params) and is exercised end-to-end by the Dashboard integration
 * test in `useDashboardData.test.ts`; one representative test here
 * is enough to lock the HTTP contract.
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
import { utilizationService } from '@/services/dispatch/utilization';

describe('utilizationService', () => {
  it('daily() GETs /dispatch/utilization/daily with the date-range params', async () => {
    vi.mocked(request.get).mockResolvedValueOnce({
      code: 0,
      data: [
        {
          date: '2026-10-01',
          vehicleId: 1,
          plateNo: '京A1',
          dispatchedCount: 3,
          totalWeightKg: 100,
          totalVolumeM3: 1,
          utilizationRate: 0.5,
        },
      ],
    });

    await utilizationService.daily({
      dateStart: '2026-10-01',
      dateEnd: '2026-10-07',
      vehicleId: 1,
    });

    const [path, opts] = vi.mocked(request.get).mock.calls[0];
    expect(path).toBe('/api/v1/dispatch/utilization/daily');
    expect(opts?.params).toEqual({
      dateStart: '2026-10-01',
      dateEnd: '2026-10-07',
      vehicleId: 1,
    });
  });
});
