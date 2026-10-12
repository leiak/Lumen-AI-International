/**
 * useVehicleList — fetch a page of vehicles (Iteration 4.1 PR6).
 *
 * Thin React wrapper over `vehicleService.list` so a List page can hold
 * loading / error state in one hook and pass the rows + setters down
 * without each page re-implementing the same try/catch dance.
 *
 * Usage:
 *   const { rows, loading, error, reload, page, setPage } = useVehicleList();
 *
 * Pages that need to filter by status / type should pass the filter in
 * via the `reloadWith(filter)` signature; the hook keeps the latest
 * params in state so subsequent `reload()` calls re-fetch with the
 * same filter AND subscribers re-render with the new `params`.
 */

import { useCallback, useEffect, useState } from 'react';
import type { ListVehiclesParams } from '@/services/dispatch/vehicle';
import { vehicleService, type WmsVehicle } from '@/services/dispatch/vehicle';

export interface UseVehicleListResult {
  rows: WmsVehicle[];
  loading: boolean;
  error: string | null;
  /** Re-fetch with the most recent params. */
  reload: () => Promise<void>;
  /** Re-fetch with a new params object. */
  reloadWith: (params: ListVehiclesParams) => Promise<void>;
  /** Currently applied params (last seen by reload). */
  params: ListVehiclesParams;
  /** Page bookkeeping (1-based). */
  page: number;
  setPage: (n: number) => void;
}

const DEFAULT_PARAMS: ListVehiclesParams = { current: 1, size: 20 };

export function useVehicleList(
  initial: ListVehiclesParams = DEFAULT_PARAMS,
): UseVehicleListResult {
  const [rows, setRows] = useState<WmsVehicle[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [params, setParams] = useState<ListVehiclesParams>(initial);
  const [page, setPage] = useState<number>(initial.current ?? 1);

  const reloadWith = useCallback(
    async (next: ListVehiclesParams): Promise<void> => {
      setLoading(true);
      setError(null);
      setParams(next);
      try {
        const res = await vehicleService.list(next);
        setRows(res.records ?? []);
      } catch (err) {
        const msg = err instanceof Error ? err.message : String(err);
        setError(msg);
        setRows([]);
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

  // Initial fetch on mount. We intentionally ignore exhaustive-deps
  // because `initial` is a prop / default and we don't want a re-fetch
  // every time the hook re-renders.
  useEffect(() => {
    void reloadWith(initial);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return {
    rows,
    loading,
    error,
    reload,
    reloadWith,
    params,
    page,
    setPage,
  };
}
