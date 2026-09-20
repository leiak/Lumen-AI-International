/**
 * Lumen Admin — silent refresh.
 *
 * The existing request.ts 401 interceptor already does refresh+retry; this
 * module extracts the single-flight refresh logic so it can be unit-tested
 * (Task 4.7) and reused outside the interceptor (e.g. by AuthProvider when
 * a token-expiry timer fires — future work).
 *
 * Concurrency: when N parallel requests all see 401, only one refresh is
 * issued; the others await the same in-flight Promise.
 */
import {
  clearAuthTokens,
  getRefreshToken,
  setAuthTokens,
} from './tokenStorage';

let inflight: Promise<string> | null = null;

export async function silentRefresh(): Promise<string> {
  if (inflight) return inflight;
  const oldRefresh = getRefreshToken();
  if (!oldRefresh) throw new Error('no refresh token');

  inflight = (async () => {
    try {
      const resp = await fetch('/api/v1/auth/refresh', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ refreshToken: oldRefresh }),
      });
      if (!resp.ok) throw new Error(`refresh failed ${resp.status}`);
      const body = await resp.json();
      if (body.code !== 0 || !body.data?.accessToken) {
        throw new Error(body.message || 'refresh failed');
      }
      setAuthTokens(body.data.accessToken, body.data.refreshToken ?? oldRefresh);
      return body.data.accessToken as string;
    } catch (e) {
      clearAuthTokens();
      throw e;
    } finally {
      inflight = null;
    }
  })();

  return inflight;
}

/** Test-only escape hatch. Do NOT call from production code. */
export function __resetInflightForTests(): void {
  inflight = null;
}