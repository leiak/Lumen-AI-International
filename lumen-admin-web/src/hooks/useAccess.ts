/**
 * Lumen Admin — useAccess hook.
 *
 * Reactive accessor for the current user's permission / role context.
 * Reads `AuthContext` directly so consumers re-render whenever the
 * underlying user or loading state changes (login, logout, refresh).
 *
 * Throws when used outside `<AuthProvider>` so a missing provider surfaces
 * at the call site instead of silently returning deny-all.
 *
 * Companion to the non-React `getAccess()` exported from `@/access`: that
 * helper is for event handlers / utilities that can't call `useContext`;
 * components should always prefer this hook.
 */

import { useContext, useMemo } from 'react';
import type { Access } from '@/access';
import { AuthContext } from '@/auth/AuthProvider';

export function useAccess(): Access {
  const auth = useContext(AuthContext);
  if (!auth) {
    throw new Error('useAccess must be used inside <AuthProvider>');
  }
  return useMemo<Access>(() => {
    const perms = auth.user?.perms ?? [];
    const roles = auth.user?.roles ?? [];
    return {
      isReady: !auth.isLoading,
      isAuthenticated: auth.user != null,
      perms,
      roles,
      canRead: (perm) => perms.includes(perm),
      canAny: (xs) => xs.some((p) => perms.includes(p)),
      canAll: (xs) => xs.every((p) => perms.includes(p)),
      hasRole: (role) =>
        roles.some((r) => r.toUpperCase() === role.toUpperCase()),
    };
    // Recompute when user or loading state flips; the inner predicates
    // close over fresh perms/roles on every update.
  }, [auth.user, auth.isLoading]);
}