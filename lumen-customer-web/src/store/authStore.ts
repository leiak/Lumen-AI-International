import { create } from 'zustand';
import { persist } from 'zustand/middleware';

/**
 * 4.5A.4-T4.2 — Portal auth store.
 *
 * Persists token + refreshToken + userId + activeCustomerId to localStorage so
 * page refresh keeps the user signed in (JWT validation still happens on the
 * backend via the Bearer header).
 *
 * Username / realName are not persisted — they're refreshed from /me on demand.
 */

export interface AuthState {
  /** Bearer access token for /api/** (aud=portal, includes active_customer_id). */
  token: string | null;
  /** Refresh token (currently re-issued the same value per backend 4.5A.3). */
  refreshToken: string | null;
  userId: number | null;
  username: string | null;
  realName: string | null;
  /** Active customer — null until the user finishes select-customer (if multi). */
  activeCustomerId: number | null;

  /** Populate everything from a successful login or select-customer response. */
  setLogin: (data: {
    token: string;
    userId: number;
    username: string;
    realName: string;
    activeCustomerId: number | null;
    refreshToken?: string | null;
  }) => void;
  /** Update only the token pair (used by the 401-refresh interceptor). */
  setTokens: (token: string, refreshToken: string | null) => void;
  /** Persist the active customer choice after a successful select-customer. */
  setActiveCustomer: (activeCustomerId: number) => void;
  /** Clear all auth state and remove the persisted slice. */
  logout: () => void;
}

export const useAuthStore = create<AuthState>()(
  persist(
    (set) => ({
      token: null,
      refreshToken: null,
      userId: null,
      username: null,
      realName: null,
      activeCustomerId: null,

      setLogin: (data) =>
        set({
          token: data.token,
          refreshToken: data.refreshToken ?? null,
          userId: data.userId,
          username: data.username,
          realName: data.realName,
          activeCustomerId: data.activeCustomerId,
        }),

      setTokens: (token, refreshToken) => set({ token, refreshToken }),

      setActiveCustomer: (activeCustomerId) => set({ activeCustomerId }),

      logout: () =>
        set({
          token: null,
          refreshToken: null,
          userId: null,
          username: null,
          realName: null,
          activeCustomerId: null,
        }),
    }),
    {
      name: 'lumen-portal-auth',
      partialize: (state) => ({
        token: state.token,
        refreshToken: state.refreshToken,
        userId: state.userId,
        activeCustomerId: state.activeCustomerId,
      }),
    }
  )
);
