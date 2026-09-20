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

export interface AuthContextValue extends AuthState {
  login: (accessToken: string, refreshToken: string) => Promise<void>;
  logout: () => void;
  refresh: () => Promise<string>;
  setUser: (user: CurrentUser | null) => void;
}
