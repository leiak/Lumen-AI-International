import axios, { AxiosError } from 'axios';
import { useAuthStore } from '../store/authStore';

/**
 * 4.5A.4-T4.2 — Shared axios instance for all portal API calls.
 *
 * - Attaches `Authorization: Bearer <token>` from the auth store on every request.
 * - On 401, attempts a single transparent refresh against
 *   POST /api/auth/portal/refresh (X-Refresh-Token header).  Retries the original
 *   request once with the new token.
 * - If refresh fails, or no refresh token is available, clears auth state and
 *   redirects to /login.
 *
 * Backend tenancy: per the T3.5 fix, the auth controller seeds tenant context
 * itself when X-Tenant-Id is missing.  We don't send X-Tenant-Id explicitly.
 */

export const http = axios.create({
  baseURL: '/api',
  // The portal audience is encoded in the JWT itself (aud=portal), so all
  // non-auth portal endpoints (/api/customer/*) share the same axios instance.
});

http.interceptors.request.use((cfg) => {
  const token = useAuthStore.getState().token;
  if (token) {
    cfg.headers.set('Authorization', `Bearer ${token}`);
  }
  return cfg;
});

http.interceptors.response.use(
  (r) => r,
  async (err: AxiosError) => {
    if (err.response?.status !== 401) {
      return Promise.reject(err);
    }

    const refreshToken = useAuthStore.getState().refreshToken;

    // If we have no refresh token (or this is the refresh call itself), bail.
    if (!refreshToken || err.config?.url?.includes('/auth/portal/refresh')) {
      useAuthStore.getState().logout();
      if (typeof window !== 'undefined') window.location.href = '/login';
      return Promise.reject(err);
    }

    try {
      const r = await axios.post<{ data: { token: string } }>(
        '/api/auth/portal/refresh',
        null,
        { headers: { 'X-Refresh-Token': refreshToken } }
      );
      const { token: newToken } = r.data.data;

      // 4.5A simplification: refresh endpoint only re-issues the access token;
      // the refresh token stays the same.
      useAuthStore.getState().setTokens(newToken, refreshToken);

      const original = err.config;
      if (!original) return Promise.reject(err);
      original.headers.set('Authorization', `Bearer ${newToken}`);
      return http(original);
    } catch {
      useAuthStore.getState().logout();
      if (typeof window !== 'undefined') window.location.href = '/login';
      return Promise.reject(err);
    }
  }
);
