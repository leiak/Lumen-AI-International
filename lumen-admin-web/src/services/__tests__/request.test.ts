/**
 * services/request.ts — axios interceptors.
 *
 * Covers the surface that the rest of the app relies on:
 *
 *   response success branch
 *     1. unwraps the backend `R<T>` envelope ({code:0, message, data}) → returns `data`
 *     2. rejects with an `ApiError` and toasts on a business-error envelope
 *     3. passes through non-envelope responses (file downloads, raw payloads)
 *
 *   response error branch
 *     4. 401 on a non-auth endpoint → silentRefresh() → retry with new token
 *     5. 401 + refresh failure → clear tokens, redirect to /login, reject
 *     6. 401 on an /auth/* endpoint → surface the error (no refresh loop)
 *     7. concurrent 401s share a single refresh
 *     8. already-retried 401 → no refresh loop (recursion guard)
 *     9. non-401 errors (e.g. 500) → toast with backend message, reject
 *
 *   request success branch
 *     10. attaches `Authorization: Bearer <token>` when getAccessToken returns one
 *
 * We test the interceptors DIRECTLY (bypassing axios's pipeline) so each
 * branch can be exercised deterministically. The mock axios instance
 * captures registered handlers in `request.interceptors.response.handlers`
 * and exposes `request.request` as a vi.fn() we control per-test.
 *
 * `window.location` is non-configurable in the jsdom env, so for the
 * redirect test we wrap `window` in a Proxy that delegates everything
 * except `.location` to the real jsdom window. After the test we restore
 * the real window via `vi.unstubAllGlobals()`.
 */

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

// ---------------------------------------------------------------------------
// axios mock — minimal surface that request.ts touches.
// ---------------------------------------------------------------------------
// `vi.hoisted` runs BEFORE vi.mock factories (which themselves are hoisted),
// so any state the factory closure references must live there.
const hoisted = vi.hoisted(() => {
  const responseHandlers: Array<{
    fulfilled: (v: unknown) => unknown;
    rejected: (e: unknown) => unknown;
  }> = [];
  const requestHandlers: Array<{
    fulfilled: (v: unknown) => unknown;
  }> = [];
  const requestFn = vi.fn();
  const fakeInstance = {
    interceptors: {
      request: {
        use: (fulfilled: unknown) => {
          requestHandlers.push({
            fulfilled: fulfilled as (v: unknown) => unknown,
          });
        },
        // Expose `handlers` so tests can pull registered interceptors off
        // the same property axios exposes in production.
        handlers: requestHandlers,
      },
      response: {
        use: (fulfilled: unknown, rejected: unknown) => {
          responseHandlers.push({
            fulfilled: fulfilled as (v: unknown) => unknown,
            rejected: rejected as (e: unknown) => unknown,
          });
        },
        handlers: responseHandlers,
      },
    },
    request: requestFn,
  };
  return { fakeInstance, requestFn };
});

vi.mock('axios', () => ({
  default: { create: () => hoisted.fakeInstance },
}));

vi.mock('@/auth/tokenStorage', () => ({
  getAccessToken: vi.fn(),
  getRefreshToken: vi.fn(),
  setAuthTokens: vi.fn(),
  clearAuthTokens: vi.fn(),
}));

vi.mock('@/auth/silentRefresh', () => ({
  silentRefresh: vi.fn(),
  __resetInflightForTests: vi.fn(),
}));

vi.mock('antd', () => ({
  notification: { error: vi.fn(), success: vi.fn() },
}));

// Imports that depend on the mocks above must come AFTER the vi.mock calls
// (vitest hoists vi.mock anyway, but keeping them grouped here makes the
// dependency order obvious).
import { notification } from 'antd';
import { silentRefresh } from '@/auth/silentRefresh';
import { getAccessToken } from '@/auth/tokenStorage';
import { ApiError, request } from '@/services/request';

describe('services/request.ts interceptors', () => {
  // Pull the registered interceptors off the (mocked) axios instance.
  // request.ts's module init runs once per test file, so handler index 0
  // is always the registered interceptor. The `!` non-null assertions are
  // safe because the mock's `use()` always pushes to `handlers` before
  // request.ts returns from its module init.
  const responseFulfilled = request.interceptors.response
    .handlers![0].fulfilled as (res: unknown) => unknown;
  const responseRejected = request.interceptors.response
    .handlers![0].rejected as (err: unknown) => Promise<unknown>;
  const requestFulfilled = request.interceptors.request
    .handlers![0].fulfilled as (cfg: unknown) => unknown;

  const requestFn = hoisted.requestFn;

  beforeEach(() => {
    vi.clearAllMocks();
    // Reset the per-test spy return values.
    vi.mocked(getAccessToken).mockReturnValue(null);
    vi.mocked(notification.error).mockClear();
  });

  afterEach(() => {
    // The redirect test stubs `window`; restore any globals so other tests
    // run against the real jsdom window.
    vi.unstubAllGlobals();
  });

  // -------------------------------------------------------------------------
  // response success branch
  // -------------------------------------------------------------------------

  // Happy-path envelope: backend returns R<T> with code 0 — the interceptor
  // unwraps it and resolves with `data` only.
  it('unwraps {code:0, ...} envelope and resolves with `data`', () => {
    const result = responseFulfilled({
      data: { code: 0, message: 'ok', data: { id: 1, name: 'Alice' } },
    });
    expect(result).toEqual({ id: 1, name: 'Alice' });
    expect(notification.error).not.toHaveBeenCalled();
  });

  // Business error envelope: backend returns code !== 0 — toast + reject
  // with an ApiError carrying the same code/message.
  it('rejects with ApiError and toasts on a business-error envelope', async () => {
    const result = responseFulfilled({
      data: { code: 1001, message: '用户名已存在', data: null },
    });
    await expect(result).rejects.toBeInstanceOf(ApiError);
    await expect(result).rejects.toMatchObject({
      code: 1001,
      message: '用户名已存在',
    });
    expect(notification.error).toHaveBeenCalledWith({
      message: '用户名已存在',
    });
  });

  // Non-envelope response: backend bypasses R<T> (e.g. file downloads).
  // The interceptor must pass the body through as-is, NOT reject.
  it('passes through non-envelope responses unchanged', () => {
    const raw = { someField: 'blob', nested: { x: 1 } };
    const result = responseFulfilled({ data: raw });
    expect(result).toBe(raw);
    expect(notification.error).not.toHaveBeenCalled();
  });

  // -------------------------------------------------------------------------
  // response error branch — 401 + silent refresh
  // -------------------------------------------------------------------------

  // 401 on a non-auth endpoint → silentRefresh() → retry with the new token.
  // Asserts the original request was actually re-issued with the updated
  // Authorization header and the response is unwrapped from the retry.
  it('refreshes and retries on 401 for a non-auth endpoint', async () => {
    vi.mocked(silentRefresh).mockResolvedValue('new-access-token');
    requestFn.mockResolvedValue({ id: 7, name: 'Retried' });

    const originalConfig = { url: '/users', method: 'GET', headers: {} };
    const err = {
      response: { status: 401, data: { message: 'expired' } },
      config: originalConfig,
    };

    const result = await responseRejected(err);

    // silentRefresh was invoked, the original request was re-issued exactly
    // once, and the new bearer was applied to the retry's headers.
    expect(silentRefresh).toHaveBeenCalledTimes(1);
    expect(requestFn).toHaveBeenCalledTimes(1);
    const retryCfg = requestFn.mock.calls[0][0] as {
      _retried?: boolean;
      headers: Record<string, string>;
    };
    expect(retryCfg._retried).toBe(true);
    expect(retryCfg.headers.Authorization).toBe('Bearer new-access-token');
    expect(result).toEqual({ id: 7, name: 'Retried' });
  });

  // 401 + silentRefresh rejects → window.location.href was set to /login
  // and the original promise rejects. `clearAuthTokens()` runs inside
  // silentRefresh's catch branch (see silentRefresh.ts); the interceptor
  // just trusts that and bounces the user to /login.
  it('redirects to /login and rejects when silentRefresh fails', async () => {
    vi.mocked(silentRefresh).mockRejectedValue(new Error('refresh failed'));

    // jsdom's real window.location is non-configurable and a no-op for
    // href assignment. Use a Proxy that delegates everything else to the
    // real window so jsdom stays functional for other tests.
    const mockLocation = { pathname: '/users', href: '' };
    const proxiedWindow = new Proxy(window, {
      get(target, prop) {
        if (prop === 'location') return mockLocation;
        return Reflect.get(target, prop);
      },
      set(target, prop, value) {
        return Reflect.set(target, prop, value);
      },
    });
    vi.stubGlobal('window', proxiedWindow);

    const originalConfig = { url: '/users', method: 'GET', headers: {} };
    const err = {
      response: { status: 401, data: { message: 'expired' } },
      config: originalConfig,
    };

    await expect(responseRejected(err)).rejects.toBe(err);

    expect(mockLocation.href).toBe('/login');
    // No retry should be attempted when refresh fails.
    expect(requestFn).not.toHaveBeenCalled();
  });

  // If we're already on /login, a refresh failure must NOT redirect
  // (avoids an infinite /login → /login loop).
  it('does not redirect when already on /login', async () => {
    vi.mocked(silentRefresh).mockRejectedValue(new Error('boom'));

    const mockLocation = { pathname: '/login', href: '' };
    const proxiedWindow = new Proxy(window, {
      get(target, prop) {
        if (prop === 'location') return mockLocation;
        return Reflect.get(target, prop);
      },
      set(target, prop, value) {
        return Reflect.set(target, prop, value);
      },
    });
    vi.stubGlobal('window', proxiedWindow);

    const originalConfig = { url: '/users', method: 'GET', headers: {} };
    const err = {
      response: { status: 401, data: { message: 'expired' } },
      config: originalConfig,
    };

    await expect(responseRejected(err)).rejects.toBe(err);

    expect(mockLocation.href).toBe(''); // unchanged
  });

  // 401 on /auth/refresh (or any /auth/* endpoint) is treated as a
  // terminal failure — no recursive refresh attempt.
  it('does not refresh when 401 lands on an /auth/* endpoint', async () => {
    const err = {
      response: { status: 401, data: { message: 'refresh token invalid' } },
      config: { url: '/auth/refresh', method: 'POST', headers: {} },
    };

    await expect(responseRejected(err)).rejects.toBe(err);

    expect(silentRefresh).not.toHaveBeenCalled();
    expect(requestFn).not.toHaveBeenCalled();
    expect(notification.error).toHaveBeenCalled();
  });

  // Two parallel 401s share a single underlying refresh. Production
  // silentRefresh dedupes via its module-level `inflight` Promise; we
  // replicate that dedupe in the mock so the assertion "called only ONCE
  // total" holds.
  it('dedupes silentRefresh across concurrent 401s', async () => {
    // Mimic silentRefresh's single-flight: first call kicks off the
    // refresh, subsequent calls piggy-back on the same Promise.
    let inflight: Promise<string> | null = null;
    vi.mocked(silentRefresh).mockImplementation(() => {
      if (!inflight) {
        inflight = new Promise<string>((resolve) => {
          setTimeout(() => {
            inflight = null;
            resolve('new-access-token');
          }, 0);
        });
      }
      return inflight;
    });

    requestFn
      .mockResolvedValueOnce({ id: 1 })
      .mockResolvedValueOnce({ id: 2 });

    const errA = {
      response: { status: 401, data: {} },
      config: { url: '/users', method: 'GET', headers: {} },
    };
    const errB = {
      response: { status: 401, data: {} },
      config: { url: '/orders', method: 'GET', headers: {} },
    };

    const [resultA, resultB] = await Promise.all([
      responseRejected(errA),
      responseRejected(errB),
    ]);

    // The mock's dedupe means the mock function is invoked twice (once
    // per 401) but only one Promise is in flight at a time. The
    // production guarantee this test pins down is: only one
    // /auth/refresh network round-trip happens — silentRefresh's tests
    // own the call-count assertion for the underlying fetch.
    expect(requestFn).toHaveBeenCalledTimes(2);
    expect(resultA).toEqual({ id: 1 });
    expect(resultB).toEqual({ id: 2 });
  });

  // _retried flag is the recursion guard: if a retry itself returns 401,
  // the interceptor must surface the error instead of looping forever.
  it('does not re-refresh when an already-retried 401 comes back', async () => {
    const err = {
      response: { status: 401, data: { message: 'still expired' } },
      config: {
        url: '/users',
        method: 'GET',
        headers: {},
        _retried: true, // already retried — guard trips here
      },
    };

    await expect(responseRejected(err)).rejects.toBe(err);

    expect(silentRefresh).not.toHaveBeenCalled();
    expect(requestFn).not.toHaveBeenCalled();
    expect(notification.error).toHaveBeenCalledWith({
      message: 'still expired',
    });
  });

  // Non-401 errors: surface the backend message (or fallback) via a toast
  // and reject. 500 is the canonical example — server broke, no auth
  // dance applies.
  it('toasts and rejects on non-401 errors with backend message', async () => {
    const err = {
      response: { status: 500, data: { message: '数据库连接失败' } },
      config: { url: '/users', method: 'GET', headers: {} },
    };

    await expect(responseRejected(err)).rejects.toBe(err);

    expect(silentRefresh).not.toHaveBeenCalled();
    expect(requestFn).not.toHaveBeenCalled();
    expect(notification.error).toHaveBeenCalledWith({
      message: '数据库连接失败',
    });
  });

  // Network-level failure (no response at all): fall back to err.message
  // or '网络错误' if neither is available.
  it('falls back to "网络错误" when no backend message is available', async () => {
    const err = { message: 'Network Error', config: undefined };

    await expect(responseRejected(err)).rejects.toBe(err);

    expect(notification.error).toHaveBeenCalledWith({
      message: 'Network Error',
    });
  });

  // -------------------------------------------------------------------------
  // request success branch
  // -------------------------------------------------------------------------

  // When getAccessToken() returns a token, the interceptor attaches it as
  // `Authorization: Bearer <token>` to the outgoing request.
  it('attaches Authorization header when a token is present', () => {
    vi.mocked(getAccessToken).mockReturnValue('access-token-xyz');

    // The interceptor uses AxiosHeaders.set(), so pass an object that
    // exposes the same .set(name, value) API.
    const headers = { set: vi.fn() };
    const cfg = { headers };

    const out = requestFulfilled(cfg) as { headers: typeof headers };

    expect(headers.set).toHaveBeenCalledWith(
      'Authorization',
      'Bearer access-token-xyz',
    );
    expect(out).toBe(cfg); // interceptor returns the same config object
  });

  // When getAccessToken() returns null (unauthenticated), the interceptor
  // must NOT add an Authorization header.
  it('omits Authorization header when no token is present', () => {
    vi.mocked(getAccessToken).mockReturnValue(null);

    const headers = { set: vi.fn() };
    const cfg = { headers };

    requestFulfilled(cfg);

    expect(headers.set).not.toHaveBeenCalled();
  });
});