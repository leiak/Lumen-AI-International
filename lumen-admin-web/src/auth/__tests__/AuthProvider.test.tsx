/**
 * AuthProvider + useAuth — Slice 1 of iteration 2.0 hook tests.
 *
 * Covers:
 *   - bootstrap (no token => stays unauthenticated, loading flips to false)
 *   - context shape (login / logout / refresh / setUser are functions)
 *   - setUser updates state and the isAuthenticated derived flag
 *   - logout clears the user (and dict cache)
 *   - useAuth throws when used outside the provider
 *
 * The /auth/me call inside the bootstrap effect is only reached when
 * localStorage holds an access token; we don't mock `request` here and
 * instead keep tokens empty in beforeEach so the effect short-circuits.
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, waitFor, act } from '@testing-library/react';
import { AuthProvider, AuthContext } from '@/auth/AuthProvider';
import { useAuth } from '@/auth/useAuth';
import { clearDictCache } from '@/hooks/useDict';
import type { CurrentUser } from '@/auth/types';

const wrapper = ({ children }: { children: React.ReactNode }) => (
  <AuthProvider>{children}</AuthProvider>
);

const sampleUser: CurrentUser = {
  userId: 42,
  tenantId: 1,
  username: 'alice',
  roles: ['OPS'],
  perms: ['user:list', 'role:list'],
};

describe('AuthProvider', () => {
  beforeEach(() => {
    localStorage.clear();
    sessionStorage.clear();
    clearDictCache();
    vi.clearAllMocks();
  });

  it('starts unauthenticated and finishes loading', async () => {
    const { result } = renderHook(() => useAuth(), { wrapper });
    // Initial synchronous render — loading is true before the effect runs.
    // Note: AuthContextValue exposes `user` (not perms/roles directly) — those
    // are derived by useAccess(). Keep the assertions here aligned with the
    // context shape, not the Access interface.
    expect(result.current.user).toBeNull();
    expect(result.current.isAuthenticated).toBe(false);
    expect(result.current.user?.perms ?? []).toEqual([]);
    expect(result.current.user?.roles ?? []).toEqual([]);
    // Bootstrap effect short-circuits when no token is present.
    await waitFor(() => {
      expect(result.current.isLoading).toBe(false);
    });
  });

  it('exposes login / logout / refresh / setUser as functions', async () => {
    const { result } = renderHook(() => useAuth(), { wrapper });
    await waitFor(() => expect(result.current.isLoading).toBe(false));
    expect(typeof result.current.login).toBe('function');
    expect(typeof result.current.logout).toBe('function');
    expect(typeof result.current.refresh).toBe('function');
    expect(typeof result.current.setUser).toBe('function');
  });

  it('updates user / isAuthenticated when setUser is called', async () => {
    const { result } = renderHook(() => useAuth(), { wrapper });
    await waitFor(() => expect(result.current.isLoading).toBe(false));

    act(() => {
      result.current.setUser(sampleUser);
    });

    expect(result.current.user).toEqual(sampleUser);
    expect(result.current.isAuthenticated).toBe(true);
  });

  it('clears user and tokens when logout is called', async () => {
    localStorage.setItem('lumen_access', 'fake-access');
    localStorage.setItem('lumen_refresh', 'fake-refresh');

    const { result } = renderHook(() => useAuth(), { wrapper });
    await waitFor(() => expect(result.current.isLoading).toBe(false));

    // We can't fully resolve the bootstrap `/auth/me` without mocking
    // request, but the user must already be null at this point because
    // the real call would have failed and the catch path clears state.
    // What we care about here is that logout clears tokens + user.
    act(() => {
      result.current.logout();
    });

    expect(result.current.user).toBeNull();
    expect(result.current.isAuthenticated).toBe(false);
    expect(localStorage.getItem('lumen_access')).toBeNull();
    expect(localStorage.getItem('lumen_refresh')).toBeNull();
  });

  it('useAuth throws when used outside AuthProvider', () => {
    // Suppress React's error boundary noise for the expected throw.
    const spy = vi.spyOn(console, 'error').mockImplementation(() => {});
    expect(() => renderHook(() => useAuth())).toThrow(
      /useAuth must be used inside <AuthProvider>/,
    );
    spy.mockRestore();
  });

  it('exports AuthContext for downstream providers / tests', () => {
    // Sanity check that the named export is wired up — useAccess and the
    // useAccess tests rely on it.
    expect(AuthContext).toBeDefined();
  });
});