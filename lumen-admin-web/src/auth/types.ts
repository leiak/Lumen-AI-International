/**
 * Lumen Admin — auth context type definitions.
 *
 * Kept in its own module so `AuthProvider`, `useAuth`, and any future test
 * helper can share the contract without pulling in React or axios.
 */

export interface CurrentUser {
  userId: number;
  tenantId: number;
  username: string;
  roles: string[];
  perms: string[];
}

export interface AuthState {
  user: CurrentUser | null;
  isAuthenticated: boolean;
  isLoading: boolean;
}

/**
 * Credentials accepted by AuthProvider.login(). `tenantId` is optional because
 * the backend derives tenant identity from the JWT for most flows; it's only
 * needed when the caller explicitly wants to bind a tenant (e.g. multi-tenant
 * login UI). The backend's LoginRequest treats tenantId the same way.
 */
export interface LoginRequest {
  tenantId?: number;
  username: string;
  password: string;
}

export interface AuthContextValue extends AuthState {
  /**
   * Perform the full login round-trip on behalf of the caller:
   *   1. POST /auth/login with the credentials
   *   2. Persist the returned access/refresh tokens via tokenStorage
   *   3. Hydrate the currentUser via /auth/me
   *
   * Throws on failure — the request interceptor already surfaces a
   * notification; callers can layer inline error UI on top.
   */
  login: (req: LoginRequest) => Promise<void>;
  logout: () => void;
  refresh: () => Promise<string>;
  setUser: (user: CurrentUser | null) => void;
}