/**
 * useCsExceptionList — composite hook for the /cs/exceptions list page
 * (Iteration 4.2 PR6).
 *
 * Locks:
 *   1. `reloadWith({...})` triggers a `csExceptionViewService.list`
 *      call with the provided params and surfaces `records` + `total`.
 *   2. `reload()` re-fetches with the LAST applied params (not the
 *      initial ones).
 *   3. Errors are captured in `error`, the data array is reset, and
 *      `loading` flips back to false (no hung spinner).
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { act, renderHook } from '@testing-library/react';

import { useCsExceptionList } from '@/hooks/useCsExceptionList';
import { csExceptionViewService } from '@/services/cs/exceptionView';
import type { PageResult } from '@/services/request';

vi.mock('@/services/cs/exceptionView', () => ({
  csExceptionViewService: {
    list: vi.fn(),
  },
}));

function pageResult(
  records: PageResult<unknown>['records'],
  total: number,
): PageResult<unknown> {
  return { records, total, pageNum: 1, pageSize: 20 };
}

describe('useCsExceptionList', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('reloadWith fetches and surfaces data + total on success', async () => {
    vi.mocked(csExceptionViewService.list).mockResolvedValueOnce(
      pageResult(
        [
          { exceptionId: 1, currentLevel: 'L1', severity: 'HIGH' },
          { exceptionId: 2, currentLevel: 'L2', severity: 'MEDIUM' },
        ],
        2,
      ) as never,
    );

    const { result } = renderHook(() => useCsExceptionList());

    await act(async () => {
      await result.current.reloadWith({ current: 1, size: 20, status: 'OPEN' });
    });

    expect(csExceptionViewService.list).toHaveBeenCalledTimes(1);
    expect(vi.mocked(csExceptionViewService.list).mock.calls[0][0]).toEqual({
      current: 1,
      size: 20,
      status: 'OPEN',
    });
    expect(result.current.data).toHaveLength(2);
    expect(result.current.total).toBe(2);
    expect(result.current.loading).toBe(false);
    expect(result.current.error).toBeNull();
  });

  it('reload re-fetches with the LAST params (not the initial defaults)', async () => {
    vi.mocked(csExceptionViewService.list).mockResolvedValue(
      pageResult([], 0) as never,
    );

    const { result } = renderHook(() => useCsExceptionList());
    await act(async () => {
      await result.current.reloadWith({ current: 2, size: 50, severity: 'HIGH' });
    });

    await act(async () => {
      await result.current.reload();
    });

    // The third call must use the params from the second call (with
    // severity), not the original defaults (current: 1, size: 20).
    expect(vi.mocked(csExceptionViewService.list).mock.calls[1][0]).toEqual({
      current: 2,
      size: 50,
      severity: 'HIGH',
    });
    expect(result.current.params).toEqual({
      current: 2,
      size: 50,
      severity: 'HIGH',
    });
  });

  it('captures errors and resets data + total', async () => {
    vi.mocked(csExceptionViewService.list)
      .mockResolvedValueOnce(pageResult([{ exceptionId: 1 }], 1) as never)
      .mockRejectedValueOnce(new Error('network down'));

    const { result } = renderHook(() => useCsExceptionList());
    await act(async () => {
      await result.current.reloadWith({ current: 1, size: 20 });
    });
    expect(result.current.data).toHaveLength(1);

    await act(async () => {
      await result.current.reload();
    });

    expect(result.current.error).toBe('network down');
    expect(result.current.data).toEqual([]);
    expect(result.current.total).toBe(0);
    expect(result.current.loading).toBe(false);
  });
});