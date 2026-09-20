/**
 * Lumen Admin — AuthProvider.
 *
 * Owns the in-memory `CurrentUser` and exposes login / logout / refresh to
 * descendants via React context. On mount it bootstraps from localStorage:
 * if an access token is present, it calls `GET /auth/me` to validate and
 * hydrate the user. A failed bootstrap clears tokens so the user lands on
 * /login cleanly via the existing route guard.
 *
 * Storage is delegated to `./tokenStorage` so callers (and tests) have one
 * canonical place to read/write tokens. The `request` instance already
 * handles the 401 refresh+retry dance; `refresh()` here exists for
 * caller-driven refresh (e.g. a manual "rotate token" action).
 */

import { createContext, useCallback, useEffect, useMemo, useState } from 'react';
import { request } from '@/services/request';
import type { AuthContextValue, CurrentUser } from './types';
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

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [user, setUser] = useState<CurrentUser | null>(null);
  const [isLoading, setIsLoading] = useState(true);

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

  const login = useCallback(async (accessToken: string, refreshToken: string) => {
    setAuthTokens(accessToken, refreshToken);
    const me = await request.get<any>('/auth/me');
    setUser(toCurrentUser(me));
  }, []);

  const logout = useCallback(() => {
    clearAuthTokens();
    setUser(null);
  }, []);

  const refresh = useCallback(async () => {
    const oldRefresh = getRefreshToken();
    if (!oldRefresh) throw new Error('no refresh token');
    const res = await request.post<any>('/auth/refresh', { refreshToken: oldRefresh });
    setAuthTokens(res.data.accessToken, res.data.refreshToken);
    return res.data.accessToken as string;
  }, []);

  const value = useMemo<AuthContextValue>(
    () => ({ user, isAuthenticated: !!user, isLoading, login, logout, refresh, setUser }),
    [user, isLoading, login, logout, refresh],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}
