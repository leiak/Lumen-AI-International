/**
 * useCsExceptionList — fetch a page of CS-perspective exception work
 * orders (Iteration 4.2 PR6).
 *
 * Thin React wrapper over `csExceptionViewService.list`. Mirrors the
 * `useExceptionList` hook (dispatch side) so the page surfaces the same
 * loading / error / reload-with-new-params semantics.
 *
 * Usage:
 *   const { data, loading, error, total, reload, reloadWith } =
 *     useCsExceptionList();
 *
 *   useEffect(() => { void reloadWith({ current: 1, size: 20 }); }, []);
 *
 * The hook does NOT auto-fetch on mount — the consumer controls when
 * the first fetch happens (typically on first render, by calling
 * `reloadWith(initial)`). This matches the dispatch hooks and avoids
 * surprising re-renders when the initial params object changes by ref.
 */

import { useCallback, useState } from 'react';
import {
  csExceptionViewService,
  type ExceptionSlaView,
  type ListCsExceptionsParams,
} from '@/services/cs/exceptionView';

export interface UseCsExceptionListResult {
  data: ExceptionSlaView[];
  loading: boolean;
  error: string | null;
  total: number;
  /** Re-fetch with the most recent params. */
  reload: () => Promise<void>;
  /** Re-fetch with a new params object. */
  reloadWith: (params: ListCsExceptionsParams) => Promise<void>;
  /** Last applied params (used by `reload`). */
  params: ListCsExceptionsParams;
}

const DEFAULT_PARAMS: ListCsExceptionsParams = { current: 1, size: 20 };

export function useCsExceptionList(
  initial: ListCsExceptionsParams = DEFAULT_PARAMS,
): UseCsExceptionListResult {
  const [data, setData] = useState<ExceptionSlaView[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [total, setTotal] = useState(0);
  const [params, setParams] = useState<ListCsExceptionsParams>(initial);

  const reloadWith = useCallback(
    async (next: ListCsExceptionsParams): Promise<void> => {
      setLoading(true);
      setError(null);
      setParams(next);
      try {
        const res = await csExceptionViewService.list(next);
        setData(res.records ?? []);
        setTotal(res.total ?? 0);
      } catch (err) {
        const msg = err instanceof Error ? err.message : String(err);
        setError(msg);
        setData([]);
        setTotal(0);
      } finally {
        setLoading(false);
      }
    },
    [],
  );

  const reload = useCallback(
    () => reloadWith(params),
    [reloadWith, params],
  );

  return {
    data,
    loading,
    error,
    total,
    reload,
    reloadWith,
    params,
  };
}