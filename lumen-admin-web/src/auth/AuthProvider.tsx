/**
 * Lumen Admin — AuthProvider.
 *
 * Owns the in-memory `CurrentUser` and exposes login / logout / refresh to
 * descendants via React context. On mount it bootstraps from localStorage:
 * if an access token is present, it calls `GET /auth/me` to validate and
 * hydrate the user. A failed bootstrap clears tokens so the user lands on
 * /login cleanly via the existing route guard.
 *
 * `login(req)` is the canonical entry point for new sign-ins — it owns the
 * POST /auth/login round-trip, persists tokens, and hydrates the user, so
 * pages (Login) and any future programmatic-login callers never touch
 * tokenStorage or the login HTTP call directly.
 *
 * Storage is delegated to `./tokenStorage` so callers (and tests) have one
 * canonical place to read/write tokens. The `request` instance already
 * handles the 401 refresh+retry dance; `refresh()` here exists for
 * caller-driven refresh (e.g. a manual "rotate token" action).
 */

import { createContext, useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { request } from '@/services/request';
import { setAccessSnapshot } from '@/access';
import { clearDictCache } from '@/hooks/useDict';
import type { AuthContextValue, CurrentUser, LoginRequest } from './types';
import { clearAuthTokens, getAccessToken, getRefreshToken, setAuthTokens } from './tokenStorage';

export const AuthContext = createContext<AuthContextValue | null>(null);

/**
 * Maps the /auth/me response (RbacUserDetails) to CurrentUser.
 *
 * NOTE: RbacUserDetails.getUsername() returns String.valueOf(userId) — there's
 * no username field on the backend principal. We propagate userId as username
 * for display purposes; a future /users/{id} call can enrich it.
 */
function toCurrentUser(me: any): CurrentUser {
  return {
    userId: Number(me.userId),
    tenantId: Number(me.tenantId),
    username: String(me.userId),
    roles: Array.isArray(me.roles) ? me.roles : [],
    perms: Array.isArray(me.perms) ? me.perms : [],
  };
}

/**
 * Shape of the /auth/login response body. The backend returns the standard
 * R<T> envelope, but the response interceptor unwraps to `data` already, so
 * the raw body we see here is the inner payload.
 */
interface LoginResponseBody {
  accessToken: string;
  refreshToken?: string;
  expiresIn?: number;
}

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [user, setUserRaw] = useState<CurrentUser | null>(null);
  const [isLoading, setIsLoadingRaw] = useState(true);

  // Refs mirror the React state synchronously so non-React callers that invoke
  // `getAccess()` immediately after a setter call see the freshest data —
  // not the pre-commit snapshot. The React state still drives renders; the
  // refs only exist to make `setAccessSnapshot` synchronous from the caller's
  // perspective.
  const userRef = useRef<CurrentUser | null>(null);
  const loadingRef = useRef(true);

  const setUser = useCallback((u: CurrentUser | null) => {
    userRef.current = u;
    setUserRaw(u);
    setAccessSnapshot(u, !loadingRef.current);
  }, []);

  const setIsLoading = useCallback((v: boolean) => {
    loadingRef.current = v;
    setIsLoadingRaw(v);
    setAccessSnapshot(userRef.current, !v);
  }, []);

  // Bootstrap: if access token is in localStorage, hit /auth/me to validate.
  useEffect(() => {
    let cancelled = false;
    (async () => {
      const access = getAccessToken();
      if (!access) {
        if (!cancelled) setIsLoading(false);
        return;
      }
      try {
        const me = await request.get<any>('/auth/me');
        if (!cancelled) setUser(toCurrentUser(me));
      } catch {
        if (!cancelled) {
          clearAuthTokens();
          setUser(null);
        }
      } finally {
        if (!cancelled) setIsLoading(false);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  /**
   * Canonical login flow. Owns:
   *   - the POST /auth/login HTTP call (was previously duplicated in Login page)
   *   - token persistence to localStorage via tokenStorage
   *   - /auth/me hydration into the user state
   *
   * Callers (Login page) only need to call login(req) and react to success
   * or failure via try/catch — they never reach into tokenStorage.
   */
  const login = useCallback(async (req: LoginRequest) => {
    const payload: Record<string, unknown> = {
      username: req.username,
      password: req.password,
    };
    // tenantId is optional in LoginRequest — only forward it when explicitly
    // provided. The backend treats a missing tenantId as "derive from JWT".
    if (req.tenantId !== undefined && req.tenantId !== null) {
      payload.tenantId = req.tenantId;
    }
    // Response interceptor unwraps R<T>; cast through unknown because the
    // axios overload is typed as Promise<AxiosResponse<T>>.
    const res = (await request.post<unknown>('/auth/login', payload)) as unknown as LoginResponseBody | null;
    if (!res?.accessToken) {
      throw new Error('登录响应缺少 accessToken');
    }
    // refreshToken is normally present; passing '' is the documented fallback
    // (the request interceptor needs a non-empty placeholder to keep refresh
    // attempts from looping on a stale empty value).
    setAuthTokens(res.accessToken, res.refreshToken ?? '');
    try {
      const me = await request.get<any>('/auth/me');
      setUser(toCurrentUser(me));
    } catch (meErr) {
      // Roll back partial login state so a failed /me doesn't leave stale tokens
      // that would silently force re-auth on next page load.
      clearAuthTokens();
      setUser(null);
      throw meErr;
    }
  }, []);

  const logout = useCallback(() => {
    clearAuthTokens();
    setUser(null);
    clearDictCache();
  }, []);

  const refresh = useCallback(async () => {
    const oldRefresh = getRefreshToken();
    if (!oldRefresh) throw new Error('no refresh token');
    // The response interceptor in services/request.ts already unwraps the
    // R<T> envelope and returns `body.data` directly — `res` here is the
    // inner payload, not an AxiosResponse. Cast through unknown because the
    // axios overload is typed as Promise<AxiosResponse<T>>.
    const res = (await request.post<unknown>('/auth/refresh', {
      refreshToken: oldRefresh,
    })) as unknown as { accessToken: string; refreshToken?: string } | null;
    if (!res?.accessToken) {
      throw new Error('refresh 响应缺少 accessToken');
    }
    setAuthTokens(res.accessToken, res.refreshToken ?? '');
    return res.accessToken;
  }, []);

  const value = useMemo<AuthContextValue>(
    () => ({ user, isAuthenticated: !!user, isLoading, login, logout, refresh, setUser }),
    [user, isLoading, login, logout, refresh],
  );

  // The snapshot is now mirrored synchronously inside `setUser` and
  // `setIsLoading` via the refs above — no post-commit effect needed.

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}