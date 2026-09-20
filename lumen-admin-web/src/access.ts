/**
 * Lumen Admin — access control surface.
 *
 * Two complementary APIs share the same `Access` shape:
 *
 *   1. `useAccess()` — the React hook. Reads `AuthContext` directly so
 *      consumers re-render automatically when the user, perms, or roles
 *      change (login / logout / refresh). Throws if used outside
 *      `<AuthProvider>` so wiring mistakes surface immediately.
 *
 *   2. `getAccess()` — synchronous, non-hook accessor for code that lives
 *      outside the React tree (event handlers, axios interceptors, utility
 *      helpers). It reads a module-scoped snapshot that `AuthProvider`
 *      keeps in sync with its state via `useEffect`. Snapshot access is
 *      *eventually consistent* — prefer the hook inside components.
 *
 * The truth source is always `AuthProvider`'s state; `setAccessSnapshot()`
 * is the single bridge that copies that state into the snapshot store.
 * `AuthProvider` is the only caller — pages never write the snapshot
 * directly.
 */

import type { CurrentUser } from './auth/types';

/**
 * Read-only view of the current user's authorization context plus a handful
 * of convenience predicates. All predicates are pure and side-effect-free.
 *
 * - `canRead` / `canAny` / `canAll` are CASE-SENSITIVE on the supplied perm
 *   code; backend perm strings are emitted in canonical casing by
 *   `RbacUserDetails.perms`, so a strict equality check is correct.
 * - `hasRole` is CASE-INSENSITIVE because role codes vary in casing across
 *   tenants (`SUPER_ADMIN` vs `super_admin`).
 */
export interface Access {
  /** True once AuthProvider has finished bootstrapping (token + /me resolved). */
  isReady: boolean;
  /** True if a user is currently logged in (snapshot has a user). */
  isAuthenticated: boolean;
  /** Permission strings the current user holds (e.g. "user:list", "role:assign"). */
  perms: string[];
  /** Role codes the current user holds (e.g. "SUPER_ADMIN", "OPS"). */
  roles: string[];
  /** True if the user has the exact permission. */
  canRead: (perm: string) => boolean;
  /** True if the user has ANY of the listed permissions (empty list → false). */
  canAny: (perms: string[]) => boolean;
  /** True if the user has ALL of the listed permissions (empty list → true). */
  canAll: (perms: string[]) => boolean;
  /** True if the user has the exact role code (case-insensitive match). */
  hasRole: (role: string) => boolean;
}

/**
 * Module-scoped snapshot of the latest auth state. `AuthProvider` overwrites
 * this in a `useEffect` on every user/loading transition; `getAccess()` reads
 * from here synchronously.
 *
 * Initial state is "deny-all, not ready" so callers that run before the first
 * `setAccessSnapshot` call (e.g. during cold start of a module that imports
 * `getAccess` at the top level) cannot accidentally see authenticated state.
 */
let snapshot: Access = {
  isReady: false,
  isAuthenticated: false,
  perms: [],
  roles: [],
  canRead: () => false,
  canAny: () => false,
  canAll: () => false,
  hasRole: () => false,
};

/**
 * Overwrite the module-scoped snapshot. Intended to be called only from
 * `AuthProvider` — pages should consume via `useAccess()` instead.
 *
 * The predicates are rebuilt on every update so they close over the
 * freshest `perms`/`roles` arrays; this avoids a stale-closure bug where a
 * consumer captures the predicate once and never sees later updates.
 */
export function setAccessSnapshot(user: CurrentUser | null, isReady: boolean): void {
  const perms = user?.perms ?? [];
  const roles = user?.roles ?? [];
  snapshot = {
    isReady,
    isAuthenticated: user != null,
    perms,
    roles,
    canRead: (perm) => perms.includes(perm),
    canAny: (xs) => xs.some((p) => perms.includes(p)),
    canAll: (xs) => xs.every((p) => perms.includes(p)),
    hasRole: (role) =>
      roles.some((r) => r.toUpperCase() === role.toUpperCase()),
  };
}

/**
 * Synchronous accessor for non-React callers. Returns the most recent
 * snapshot written by `AuthProvider`. Inside React components, prefer
 * `useAccess()` — it re-renders the component when the snapshot changes,
 * whereas `getAccess()` returns a frozen reference that may go stale.
 */
export function getAccess(): Access {
  return snapshot;
}