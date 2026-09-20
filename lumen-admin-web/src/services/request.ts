/**
 * Lumen Admin — axios wrapper.
 *
 * Responsibilities:
 *   - Attach `Authorization: Bearer <token>` from localStorage to every request.
 *   - Unwrap the backend's `R<T>` envelope (code/message/data) and return `data`
 *     on success; show a notification and reject on business failure.
 *   - On 401, attempt a single refresh-then-retry flow (avoids hard redirects
 *     while a single token is being refreshed). If refresh fails, clear tokens
 *     and bounce the user to /login.
 *
 * MVP caveats (TechDebt — see docs/02 业务表结构设计.md):
 *   - Tokens live in localStorage (XSS-vulnerable). Acceptable for MVP; move
 *     to httpOnly cookies once a refresh-cookie strategy lands.
 *   - We do NOT send `X-Tenant-Id` from the frontend. Tenant identity is
 *     derived server-side from the JWT (`tid` claim) — letting the client
 *     pick a tenant would invite impersonation.
 */

import axios, {
  AxiosError,
  AxiosInstance,
  AxiosRequestConfig,
  InternalAxiosRequestConfig,
} from 'axios';
import { notification } from 'antd';
import { getAccessToken, clearAuthTokens } from '@/auth/tokenStorage';
import { silentRefresh } from '@/auth/silentRefresh';

export interface ApiEnvelope<T> {
  code: number;
  message: string;
  data: T;
  traceId?: string;
  timestamp?: number;
}

export interface PageResult<T> {
  records: T[];
  total: number;
  pageNum: number;
  pageSize: number;
}

/**
 * Internal flag used by the response interceptor so a request that was
 * already retried after a refresh doesn't enter a refresh loop.
 */
interface RetryConfig extends AxiosRequestConfig {
  _retried?: boolean;
}

export const request: AxiosInstance = axios.create({
  baseURL: '/api/v1',
  timeout: 15000,
});

// ---------------------------------------------------------------------------
// Request interceptor — attach bearer token.
// ---------------------------------------------------------------------------
request.interceptors.request.use((cfg: InternalAxiosRequestConfig) => {
  const token = getAccessToken();
  if (token) {
    cfg.headers.set('Authorization', `Bearer ${token}`);
  }
  return cfg;
});

// ---------------------------------------------------------------------------
// Response interceptor — unwrap envelope, handle 401 with refresh+retry.
// ---------------------------------------------------------------------------
request.interceptors.response.use(
  (res) => {
    const body = res.data as ApiEnvelope<unknown> | undefined;
    // Some endpoints (auth/refresh, file downloads) may bypass the envelope
    // — pass through anything that doesn't look like an R<T>.
    if (!body || typeof body !== 'object' || !('code' in body)) {
      return res.data;
    }
    if (body.code === 0) {
      return body.data;
    }
    notification.error({ message: body.message || '请求失败' });
    return Promise.reject(new ApiError(body.code, body.message, body.data));
  },
  async (err: AxiosError<ApiEnvelope<unknown>>) => {
    const status = err.response?.status;
    const cfg = err.config as RetryConfig | undefined;

    if (status === 401 && cfg && !cfg._retried) {
      cfg._retried = true;
      try {
        const newToken = await silentRefresh();
        cfg.headers = cfg.headers ?? ({} as AxiosRequestConfig['headers']);
        // `headers` may be AxiosHeaders or a plain object — set both flavors.
        if (typeof (cfg.headers as { set?: unknown }).set === 'function') {
          (cfg.headers as { set: (k: string, v: string) => void }).set(
            'Authorization',
            `Bearer ${newToken}`,
          );
        } else {
          (cfg.headers as Record<string, string>).Authorization = `Bearer ${newToken}`;
        }
        return request.request(cfg);
      } catch {
        clearAuthTokens();
        // Hard redirect is intentional for MVP — keeps the auth state simple
        // and avoids stale React state carrying protected data into /login.
        if (window.location.pathname !== '/login') {
          window.location.href = '/login';
        }
        return Promise.reject(err);
      }
    }

    // Non-401 errors (or already-retried 401) — surface backend message if any.
    const msg =
      err.response?.data?.message ||
      err.message ||
      '网络错误';
    notification.error({ message: msg });
    return Promise.reject(err);
  },
);

export class ApiError extends Error {
  readonly code: number;
  readonly data: unknown;
  constructor(code: number, message: string, data: unknown) {
    super(message);
    this.name = 'ApiError';
    this.code = code;
    this.data = data;
  }
}

export default request;