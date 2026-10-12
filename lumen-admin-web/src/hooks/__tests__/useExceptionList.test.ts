/**
 * useExceptionList — composite hook for the Exception list page
 * (Iteration 4.1 PR6).
 *
 * Locks the contract:
 *   1. Mount triggers `deliveryExceptionService.page(initial)` once.
 *   2. `reloadWith({ status: 'OPEN' })` re-fetches with the filter;
 *      `reload()` re-fetches with the LAST applied filter (not initial).
 *   3. Error path: error is captured, rows reset, loading flipped off.
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { act, renderHook } from '@testing-library/react';

import { useExceptionList } from '@/hooks/useExceptionList';
import {
  deliveryExceptionService,
  type WmsDeliveryException,
} from '@/services/dispatch/exception';
import type { PageResult } from '@/services/request';

vi.mock('@/services/dispatch/exception', () => ({
  deliveryExceptionService: {
    page: vi.fn(),
  },
}));

function pageResult(
  records: WmsDeliveryException[],
  total: number,
): PageResult<WmsDeliveryException> {
  return { records, total, pageNum: 1, pageSize: 20 };
}

describe('useExceptionList', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('fetches the initial page on mount and surfaces records', async () => {
    vi.mocked(deliveryExceptionService.page).mockResolvedValueOnce(
      pageResult(
        [
          { id: 1, orderId: 7, type: 'REJECTED', status: 'OPEN' },
          { id: 2, orderId: 8, type: 'DAMAGE', status: 'INVESTIGATING' },
        ],
        2,
      ),
    );

    const { result } = renderHook(() =>
      useExceptionList({ current: 1, size: 20 }),
    );
    await act(async () => {});

    expect(deliveryExceptionService.page).toHaveBeenCalledTimes(1);
    expect(vi.mocked(deliveryExceptionService.page).mock.calls[0][0]).toEqual({
      current: 1,
      size: 20,
    });
    expect(result.current.rows).toHaveLength(2);
    expect(result.current.loading).toBe(false);
    expect(result.current.error).toBeNull();
  });

  it('reloadWith re-fetches with new params; reload re-fetches with the last', async () => {
    vi.mocked(deliveryExceptionService.page).mockResolvedValue(
      pageResult([], 0),
    );

    const { result } = renderHook(() =>
      useExceptionList({ current: 1, size: 20 }),
    );
    await act(async () => {});

    await act(async () => {
      await result.current.reloadWith({ current: 1, size: 20, status: 'OPEN' });
    });

    expect(vi.mocked(deliveryExceptionService.page).mock.calls[1][0]).toEqual({
      current: 1,
      size: 20,
      status: 'OPEN',
    });
    expect(result.current.params.status).toBe('OPEN');

    await act(async () => {
      await result.current.reload();
    });

    expect(vi.mocked(deliveryExceptionService.page).mock.calls[2][0]).toEqual({
      current: 1,
      size: 20,
      status: 'OPEN',
    });
  });

  it('captures errors and resets rows', async () => {
    vi.mocked(deliveryExceptionService.page)
      .mockResolvedValueOnce(pageResult(
        [{ id: 1, orderId: 7, type: 'REJECTED', status: 'OPEN' }],
        1,
      ))
      .mockRejectedValueOnce(new Error('boom'));

    const { result } = renderHook(() => useExceptionList());
    await act(async () => {});
    expect(result.current.rows).toHaveLength(1);

    await act(async () => {
      await result.current.reload();
    });

    expect(result.current.error).toBe('boom');
    expect(result.current.rows).toEqual([]);
    expect(result.current.loading).toBe(false);
  });
});
