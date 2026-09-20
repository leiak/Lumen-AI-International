/**
 * Lumen Admin — useAuth hook.
 *
 * Thin wrapper over React's `useContext` that enforces the `<AuthProvider>`
 * boundary: calling this hook outside the provider throws so the developer
 * sees the wiring mistake immediately rather than chasing `undefined` later.
 */

import { useContext } from 'react';
import { AuthContext } from './AuthProvider';
import type { AuthContextValue } from './types';

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used inside <AuthProvider>');
  return ctx;
}
