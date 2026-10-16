/**
 * useSlaConfigs — read-only SLA config hook (Iteration 4.2 PR6).
 *
 * Locks:
 *   1. On mount, the hook fires `slaConfigService.list` with the
 *      supplied severity / level filters and surfaces items.
 *   2. When the dependency changes, the hook refetches (verifying the
 *      dependency-change contract).
 *   3. Errors are captured and the data array is reset to `[]`.
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';

import { useSlaConfigs } from '@/hooks/useSlaConfigs';
import { slaConfigService } from '@/services/cs/slaConfig';

vi.mock('@/services/cs/slaConfig', () => ({
  slaConfigService: {
    list: vi.fn(),
  },
}));

describe('useSlaConfigs', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('loads SLA configs on mount and exposes them as data', async () => {
    vi.mocked(slaConfigService.list).mockResolvedValueOnce({
      items: [
        {
          id: 1,
          tenantId: 0,
          severity: 'HIGH',
          level: 'L1',
          responseMinutes: 30,
          resolveMinutes: 240,
          escalateToLevel: 'L2',
        },
        {
          id: 2,
          tenantId: 0,
          severity: 'HIGH',
          level: 'L2',
          responseMinutes: 30,
          resolveMinutes: 480,
          escalateToLevel: 'L3',
        },
      ],
      total: 2,
    });

    const { result } = renderHook(() => useSlaConfigs('HIGH', 'L1'));

    await waitFor(() => expect(result.current.loading).toBe(false));

    expect(slaConfigService.list).toHaveBeenCalledTimes(1);
    expect(slaConfigService.list).toHaveBeenCalledWith({
      severity: 'HIGH',
      level: 'L1',
      page: 1,
      size: 100,
    });
    expect(result.current.data).toHaveLength(2);
    expect(result.current.error).toBeNull();
  });

  it('refetches when the severity or level dependency changes', async () => {
    vi.mocked(slaConfigService.list).mockResolvedValue({ items: [], total: 0 });

    const { rerender } = renderHook(
      ({ severity, level }: { severity?: string; level?: string }) =>
        useSlaConfigs(severity, level),
      { initialProps: { severity: 'HIGH', level: 'L1' } },
    );

    await waitFor(() =>
      expect(slaConfigService.list).toHaveBeenCalledWith({
        severity: 'HIGH',
        level: 'L1',
        page: 1,
        size: 100,
      }),
    );

    rerender({ severity: 'HIGH', level: 'L2' });

    await waitFor(() =>
      expect(slaConfigService.list).toHaveBeenCalledWith({
        severity: 'HIGH',
        level: 'L2',
        page: 1,
        size: 100,
      }),
    );

    expect(slaConfigService.list).toHaveBeenCalledTimes(2);
  });

  it('captures errors and resets data', async () => {
    vi.mocked(slaConfigService.list).mockRejectedValueOnce(new Error('boom'));

    const { result } = renderHook(() => useSlaConfigs('HIGH', 'L1'));

    await waitFor(() => expect(result.current.loading).toBe(false));

    expect(result.current.error).toBe('boom');
    expect(result.current.data).toEqual([]);
  });
});