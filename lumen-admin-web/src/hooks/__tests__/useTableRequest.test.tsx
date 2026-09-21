/**
 * useTableRequest — Slice 2 of iteration 2.0 hook tests.
 *
 * Covers the two-way bridge that `useTableRequest` provides:
 *
 *   ProTable params ({ current, pageSize, ...user })
 *     → fetch ({ page: current, size: pageSize, ...user })
 *     → fetch's Promise<PageResult<T>>   (Spring Page shape)
 *     → ProTable response ({ success, data: records, total })
 *
 * We DON'T test `request` here — useTableRequest takes an opaque fetch
 * function and never touches the network itself. Tests drive the fetch
 * with a vi.fn() so we can assert on:
 *
 *   1. Happy path: returns success + records + total on resolved promise.
 *   2. Param mapping: `current` → `page`, `pageSize` → `size`, extra
 *      keys (e.g. `keyword`) pass through untouched.
 *   3. Failure path: rejected fetch surfaces as
 *      `{ success: false, data: [], total: 0 }` (and logs to console.error)
 *      so ProTable's error UI / onLoadFailed fires without crashing.
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook } from '@testing-library/react';
import { useTableRequest } from '@/hooks/useTableRequest';

describe('useTableRequest', () => {
  let errorSpy: ReturnType<typeof vi.spyOn>;

  beforeEach(() => {
    // Silence useTableRequest's `console.error('[useTableRequest] fetch failed', err)`
    // during the failure-path test so the test output stays clean. Restore in afterEach
    // via mockRestore so the spy doesn't leak into other test files.
    errorSpy = vi.spyOn(console, 'error').mockImplementation(() => {});
  });

  it('returns success + records + total on a resolved fetch', async () => {
    const fetchFn = vi.fn(async () => ({
      records: [
        { id: 1, name: 'a' },
        { id: 2, name: 'b' },
      ],
      total: 2,
      pageNum: 1,
      pageSize: 20,
    }));

    const { result } = renderHook(() => useTableRequest(fetchFn));
    const response = await result.current({ current: 1, pageSize: 20 });

    expect(response.success).toBe(true);
    expect(response.data).toEqual([
      { id: 1, name: 'a' },
      { id: 2, name: 'b' },
    ]);
    expect(response.total).toBe(2);
  });

  it('maps ProTable `current`/`pageSize` to backend `page`/`size`', async () => {
    const fetchFn = vi.fn(async () => ({ records: [], total: 0 }));

    const { result } = renderHook(() => useTableRequest(fetchFn));
    await result.current({ current: 3, pageSize: 50 });

    expect(fetchFn).toHaveBeenCalledTimes(1);
    expect(fetchFn).toHaveBeenCalledWith(
      expect.objectContaining({ page: 3, size: 50 }),
    );
  });

  it('passes extra ProTable params (e.g. keyword) through to the fetch unchanged', async () => {
    const fetchFn = vi.fn(async () => ({ records: [], total: 0 }));

    const { result } = renderHook(() => useTableRequest(fetchFn));
    await result.current({ current: 1, pageSize: 20, keyword: 'alice' });

    expect(fetchFn).toHaveBeenCalledWith(
      expect.objectContaining({
        page: 1,
        size: 20,
        keyword: 'alice',
      }),
    );
  });

  it('returns an empty failure response when the fetch rejects', async () => {
    const fetchFn = vi.fn(async () => {
      throw new Error('network');
    });

    const { result } = renderHook(() => useTableRequest(fetchFn));
    const response = await result.current({ current: 1, pageSize: 20 });

    expect(response.success).toBe(false);
    expect(response.data).toEqual([]);
    expect(response.total).toBe(0);
    // The hook logs the failure so devs can see it in the console; assert
    // it was called with the [useTableRequest] prefix so it can be grep'd.
    expect(errorSpy).toHaveBeenCalled();
    expect(String(errorSpy.mock.calls[0][0])).toContain('[useTableRequest]');
  });

  it('returns a stable callback across re-renders (deps = [fetch])', () => {
    const fetchFn = vi.fn(async () => ({ records: [], total: 0 }));
    const { result, rerender } = renderHook(() => useTableRequest(fetchFn));

    const first = result.current;
    rerender();
    const second = result.current;

    expect(first).toBe(second);
  });
});
