/**
 * useTableRequest — wraps ProTable's request signature.
 *
 * ProTable calls `request(params, sort, filter)` where params is
 * `{ current, pageSize, ...userParams }`. The backend expects
 * `{ page, size, ...userParams }`. The backend returns
 * `{ records: T[], total: number, ... }` (Spring Page shape).
 *
 * This hook bridges the two so callers write only the fetch logic.
 *
 * Usage:
 *   const request = useTableRequest<SysUser>(async (params) => {
 *     const res = await api.listUsers(params);
 *     return res; // { records: SysUser[], total: number }
 *   });
 *
 *   <ProTable request={request} rowKey="id" ... />
 *
 * The returned function is stable across renders (useCallback with deps
 * `[fetch]`), so it can safely be passed to ProTable without breaking
 * its internal optimizations.
 */

import { useCallback } from 'react';

/** ProTable's request params (subset we care about). */
export interface TableRequestParams {
  current: number;
  pageSize: number;
  [key: string]: unknown;
}

/** Spring Page shape returned by the backend. */
export interface PageResult<T> {
  records: T[];
  total: number;
  size?: number;
  current?: number;
  /** Backend may also surface pages / searchCount etc.; we ignore those. */
  [key: string]: unknown;
}

/** ProTable's expected response shape. */
export interface TableResponse<T> {
  data: T[];
  total: number;
  success: boolean;
}

/**
 * Fetch function signature that callers provide.
 * Receives backend-shaped params (`page`, `size`, plus user params).
 * Returns the backend's `PageResult<T>` directly.
 */
export type FetchPage<T> = (params: {
  page: number;
  size: number;
  [key: string]: unknown;
}) => Promise<PageResult<T>>;

/**
 * Adapter hook: ProTable request → fetch → ProTable response.
 *
 * - `current` (1-based) → `page` (1-based; matches backend)
 * - `pageSize` → `size`
 * - `success: true` on any successful return (no partial failures)
 * - `success: false` (with `total: 0`) on thrown errors, so ProTable surfaces the
 *   failure signal via its error UI / `onLoadFailed` instead of crashing. The
 *   axios interceptor still shows the user-facing `notification.error`.
 */
export function useTableRequest<T>(
  fetch: FetchPage<T>,
): (params: TableRequestParams) => Promise<TableResponse<T>> {
  return useCallback(
    async (params: TableRequestParams): Promise<TableResponse<T>> => {
      const { current, pageSize, ...rest } = params;
      try {
        const page = await fetch({ page: current, size: pageSize, ...rest });
        return {
          data: page.records,
          total: page.total,
          success: true,
        };
      } catch (err) {
        console.error('[useTableRequest] fetch failed', err);
        return { data: [], total: 0, success: false };
      }
    },
    [fetch],
  );
}
