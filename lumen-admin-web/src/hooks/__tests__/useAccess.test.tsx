/**
 * useAccess — Slice 2 of iteration 2.0 hook tests.
 *
 * `useAccess` reads `AuthContext` directly and builds the predicate set
 * (`canRead` / `canAny` / `canAll` / `hasRole`) plus the derived flags
 * (`isReady`, `isAuthenticated`). These tests exercise the predicates
 * with several perm / role combinations and also confirm the hook
 * throws when consumed outside `<AuthProvider>`.
 *
 * No request mocking is required — `useAccess` is purely a derivation
 * over the context value, and we drive its input via a hand-built
 * `<AuthContext.Provider>` wrapper.
 */

import { describe, it, expect, vi } from 'vitest';
import { renderHook } from '@testing-library/react';
import { useAccess } from '@/hooks/useAccess';
import { AuthContext } from '@/auth/AuthProvider';
import type { AuthContextValue, CurrentUser } from '@/auth/types';

function makeWrapper(
  perms: string[],
  roles: string[] = [],
  opts: Partial<{ isLoading: boolean }> = {},
): React.FC<{ children: React.ReactNode }> {
  const user: CurrentUser | null =
    perms.length > 0 || roles.length > 0
      ? { userId: 1, tenantId: 1, username: 'tester', roles, perms }
      : null;
  const value: AuthContextValue = {
    user,
    isAuthenticated: !!user,
    isLoading: opts.isLoading ?? false,
    login: async () => {},
    logout: () => {},
    refresh: async () => 'token',
    setUser: () => {},
  };
  return ({ children }) => (
    <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
  );
}

describe('useAccess', () => {
  it('canRead returns true when perm is in user perms', () => {
    const { result } = renderHook(() => useAccess(), {
      wrapper: makeWrapper(['user:list', 'role:list']),
    });
    expect(result.current.canRead('user:list')).toBe(true);
    expect(result.current.canRead('role:list')).toBe(true);
    expect(result.current.canRead('dict:list')).toBe(false);
  });

  it('canAny returns true if any perm matches', () => {
    const { result } = renderHook(() => useAccess(), {
      wrapper: makeWrapper(['user:list']),
    });
    expect(result.current.canAny(['user:list', 'role:list'])).toBe(true);
    expect(result.current.canAny(['role:list', 'dict:list'])).toBe(false);
  });

  it('canAny returns false on empty list', () => {
    const { result } = renderHook(() => useAccess(), {
      wrapper: makeWrapper(['user:list']),
    });
    expect(result.current.canAny([])).toBe(false);
  });

  it('canAll returns true only if all perms match', () => {
    const { result } = renderHook(() => useAccess(), {
      wrapper: makeWrapper(['user:list', 'role:list']),
    });
    expect(result.current.canAll(['user:list', 'role:list'])).toBe(true);
    expect(result.current.canAll(['user:list', 'dict:list'])).toBe(false);
  });

  it('canAll returns true on empty list (vacuously satisfied)', () => {
    const { result } = renderHook(() => useAccess(), {
      wrapper: makeWrapper(['user:list']),
    });
    expect(result.current.canAll([])).toBe(true);
  });

  it('hasRole is case-insensitive', () => {
    const { result } = renderHook(() => useAccess(), {
      wrapper: makeWrapper([], ['SUPER_ADMIN']),
    });
    expect(result.current.hasRole('SUPER_ADMIN')).toBe(true);
    expect(result.current.hasRole('super_admin')).toBe(true);
    expect(result.current.hasRole('Super_Admin')).toBe(true);
    expect(result.current.hasRole('USER')).toBe(false);
  });

  it('reflects isAuthenticated and isReady from the auth context', () => {
    const { result } = renderHook(() => useAccess(), {
      wrapper: makeWrapper(['user:list'], ['OPS']),
    });
    expect(result.current.isAuthenticated).toBe(true);
    expect(result.current.isReady).toBe(true);

    const loading = renderHook(() => useAccess(), {
      wrapper: makeWrapper(['user:list'], ['OPS'], { isLoading: true }),
    });
    expect(loading.result.current.isReady).toBe(false);
  });

  it('exposes perms and roles arrays', () => {
    const { result } = renderHook(() => useAccess(), {
      wrapper: makeWrapper(['a:b', 'c:d'], ['OPS', 'AUDIT']),
    });
    expect(result.current.perms).toEqual(['a:b', 'c:d']);
    expect(result.current.roles).toEqual(['OPS', 'AUDIT']);
  });

  it('returns empty perms/roles and deny-all predicates when user is null', () => {
    const { result } = renderHook(() => useAccess(), {
      wrapper: makeWrapper([], []),
    });
    expect(result.current.perms).toEqual([]);
    expect(result.current.roles).toEqual([]);
    expect(result.current.isAuthenticated).toBe(false);
    expect(result.current.canRead('anything')).toBe(false);
    expect(result.current.canAny(['anything'])).toBe(false);
    expect(result.current.canAll([])).toBe(true); // vacuously true
    expect(result.current.hasRole('SUPER_ADMIN')).toBe(false);
  });

  it('throws when used outside AuthProvider', () => {
    const spy = vi.spyOn(console, 'error').mockImplementation(() => {});
    expect(() => renderHook(() => useAccess())).toThrow(
      /useAccess must be used inside <AuthProvider>/,
    );
    spy.mockRestore();
  });
});