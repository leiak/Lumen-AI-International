/**
 * useDict — Slice 2 of iteration 2.0 hook tests.
 *
 * Covers:
 *   - happy-path fetch + valueEnum shape (label -> text, status -> BadgeStatus)
 *   - module-level cache hit avoids a second network call
 *   - sessionStorage round-trip (cache survives a fresh hook instance
 *     after `clearDictCache` resets only the in-memory map)
 *   - fetch failure surfaces as `error` and leaves `valueEnum` empty
 *   - distinct codes are cached independently
 *
 * `useDict` calls `request.get` directly, so we mock the `@/services/request`
 * module. The default export of that module is the axios instance — Vitest's
 * `vi.mock` matches it.
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { useDict, clearDictCache } from '@/hooks/useDict';

// Mock the request module so the hook never hits a real axios instance.
vi.mock('@/services/request', () => {
  const get = vi.fn();
  return {
    default: { get },
    request: { get },
    get,
  };
});

// Pull the mocked `get` back out for assertions.
import request from '@/services/request';
const mockGet = request.get as unknown as ReturnType<typeof vi.fn>;

describe('useDict', () => {
  beforeEach(() => {
    sessionStorage.clear();
    clearDictCache();
    mockGet.mockReset();
  });

  it('fetches dict items on first call and maps them to valueEnum', async () => {
    mockGet.mockResolvedValueOnce([
      { code: 'M', label: '男', status: 1 },
      { code: 'F', label: '女', status: 1 },
    ]);

    const { result } = renderHook(() => useDict('gender'));

    await waitFor(() => {
      expect(result.current.loading).toBe(false);
    });

    expect(result.current.error).toBeNull();
    expect(result.current.valueEnum).toEqual({
      M: { text: '男', status: 'success' },
      F: { text: '女', status: 'success' },
    });
    expect(mockGet).toHaveBeenCalledTimes(1);
    expect(mockGet).toHaveBeenCalledWith('/dicts/gender/items');
  });

  it('translates non-1 status to BadgeStatus "default"', async () => {
    mockGet.mockResolvedValueOnce([
      { code: 'A', label: 'Active', status: 1 },
      { code: 'I', label: 'Inactive', status: 0 },
    ]);

    const { result } = renderHook(() => useDict('status-mix'));
    await waitFor(() => expect(result.current.loading).toBe(false));

    expect(result.current.valueEnum).toEqual({
      A: { text: 'Active', status: 'success' },
      I: { text: 'Inactive', status: 'default' },
    });
  });

  it('uses module-level cache on subsequent calls within the same session', async () => {
    mockGet.mockResolvedValueOnce([{ code: 'X', label: 'X', status: 1 }]);

    const { result: r1 } = renderHook(() => useDict('cache-test'));
    await waitFor(() => expect(r1.current.loading).toBe(false));
    expect(mockGet).toHaveBeenCalledTimes(1);

    const { result: r2 } = renderHook(() => useDict('cache-test'));
    // Second render hits the in-memory cache synchronously — loading is
    // false immediately and no extra network call is made.
    expect(r2.current.loading).toBe(false);
    expect(r2.current.valueEnum).toEqual({
      X: { text: 'X', status: 'success' },
    });
    expect(mockGet).toHaveBeenCalledTimes(1);
  });

  it('falls back to sessionStorage when the in-memory map is cold', async () => {
    // Simulate the post-reload state: in-memory Map is empty (the module
    // was just re-evaluated for the first time in this test) but
    // sessionStorage already holds a previously-fetched entry. The hook
    // should hydrate synchronously from sessionStorage without a network
    // call. We pre-populate sessionStorage with the same key shape the
    // hook writes under (`lumen:dict:<code>`).
    sessionStorage.setItem(
      'lumen:dict:persisted',
      JSON.stringify({
        P: { text: 'Persisted', status: 'success' },
      }),
    );

    const { result } = renderHook(() => useDict('persisted'));
    // Synchronous cache hit — loading flips to false on the first render.
    expect(result.current.loading).toBe(false);
    expect(result.current.valueEnum).toEqual({
      P: { text: 'Persisted', status: 'success' },
    });
    expect(mockGet).not.toHaveBeenCalled();
  });

  it('handles fetch failure gracefully (error set, valueEnum empty)', async () => {
    mockGet.mockRejectedValueOnce(new Error('network'));

    const { result } = renderHook(() => useDict('fail-test'));
    await waitFor(() => expect(result.current.loading).toBe(false));

    expect(result.current.error).toBeInstanceOf(Error);
    expect(result.current.error?.message).toBe('network');
    expect(result.current.valueEnum).toEqual({});
  });

  it('caches distinct codes independently', async () => {
    mockGet.mockImplementation(async (url: string) => {
      if (url === '/dicts/a/items') return [{ code: 'A1', label: 'A1', status: 1 }];
      if (url === '/dicts/b/items') return [{ code: 'B1', label: 'B1', status: 1 }];
      throw new Error(`unexpected url ${url}`);
    });

    const { result: ra } = renderHook(() => useDict('a'));
    const { result: rb } = renderHook(() => useDict('b'));
    await waitFor(() => {
      expect(ra.current.loading).toBe(false);
      expect(rb.current.loading).toBe(false);
    });

    expect(ra.current.valueEnum).toEqual({
      A1: { text: 'A1', status: 'success' },
    });
    expect(rb.current.valueEnum).toEqual({
      B1: { text: 'B1', status: 'success' },
    });
    expect(mockGet).toHaveBeenCalledTimes(2);

    // Re-render both — both should hit cache.
    const { result: ra2 } = renderHook(() => useDict('a'));
    const { result: rb2 } = renderHook(() => useDict('b'));
    expect(ra2.current.valueEnum).toEqual(ra.current.valueEnum);
    expect(rb2.current.valueEnum).toEqual(rb.current.valueEnum);
    expect(mockGet).toHaveBeenCalledTimes(2);
  });

  it('clearDictCache wipes both the Map and sessionStorage entries', async () => {
    mockGet.mockResolvedValueOnce([
      { code: 'Z', label: 'Z', status: 1 },
    ]);

    const { result: r1 } = renderHook(() => useDict('wipe'));
    await waitFor(() => expect(r1.current.loading).toBe(false));
    expect(sessionStorage.getItem('lumen:dict:wipe')).not.toBeNull();

    clearDictCache();
    expect(sessionStorage.getItem('lumen:dict:wipe')).toBeNull();

    // Next read goes back to the network.
    mockGet.mockResolvedValueOnce([
      { code: 'Z', label: 'Z', status: 1 },
    ]);
    const { result: r2 } = renderHook(() => useDict('wipe'));
    await waitFor(() => expect(r2.current.loading).toBe(false));
    expect(mockGet).toHaveBeenCalledTimes(2);
  });
});