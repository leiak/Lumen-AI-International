/**
 * Lumen Admin — auth token localStorage accessors.
 *
 * SSR-safe: all accessors short-circuit when `window` is undefined so this
 * module can be imported during Next.js / Vite SSR builds without throwing.
 *
 * Key naming follows the iteration-2.0 frontend deepening spec (§2.1):
 *   - `lumen_access`  — short-lived access token (bearer)
 *   - `lumen_refresh` — longer-lived refresh token
 *
 * Both `services/request.ts` (request/response interceptors) and
 * `pages/Login/index.tsx` (login handler) go through these helpers.
 */

const ACCESS_KEY = 'lumen_access';
const REFRESH_KEY = 'lumen_refresh';

export const getAccessToken = (): string | null =>
  typeof window !== 'undefined' ? localStorage.getItem(ACCESS_KEY) : null;
export const getRefreshToken = (): string | null =>
  typeof window !== 'undefined' ? localStorage.getItem(REFRESH_KEY) : null;
export const setAuthTokens = (access: string, refresh: string): void => {
  localStorage.setItem(ACCESS_KEY, access);
  localStorage.setItem(REFRESH_KEY, refresh);
};
export const clearAuthTokens = (): void => {
  localStorage.removeItem(ACCESS_KEY);
  localStorage.removeItem(REFRESH_KEY);
};
