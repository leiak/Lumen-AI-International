/**
 * useDashboardData — fetch fleet KPI + daily breakdown for the dispatch
 * dashboard (Iteration 4.1 PR6).
 *
 * Composite hook: returns `{ fleet, rows, loading, error, reload, range }`.
 * It issues BOTH the `fleet` (KPI totals) and `daily` (per-vehicle
 * breakdown) requests in parallel via `Promise.all` so the page renders
 * one cohesive state.
 *
 * Usage:
 *   const { fleet, rows, loading, reload } = useDashboardData({
 *     dateStart: '2026-10-01', dateEnd: '2026-10-07',
 *   });
 */

import { useCallback, useEffect, useState } from 'react';
import {
  utilizationService,
  type DailyUtilizationRow,
  type FleetUtilizationResponse,
} from '@/services/dispatch/utilization';

export interface DashboardRange {
  dateStart: string;       // ISO yyyy-MM-dd
  dateEnd: string;         // ISO yyyy-MM-dd
}

export interface UseDashboardDataResult {
  fleet: FleetUtilizationResponse | null;
  rows: DailyUtilizationRow[];
  loading: boolean;
  error: string | null;
  reload: () => Promise<void>;
  /** Re-fetch with a new range; updates `range` for subsequent `reload()`. */
  reloadWith: (next: DashboardRange) => Promise<void>;
  /** Most recent range passed to `reloadWith` (or initial). */
  range: DashboardRange;
}

export function useDashboardData(
  initial: DashboardRange,
): UseDashboardDataResult {
  const [fleet, setFleet] = useState<FleetUtilizationResponse | null>(null);
  const [rows, setRows] = useState<DailyUtilizationRow[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [range, setRange] = useState<DashboardRange>(initial);

  const reloadWith = useCallback(
    async (next: DashboardRange): Promise<void> => {
      setLoading(true);
      setError(null);
      setRange(next);
      try {
        const [fleetData, dailyRows] = await Promise.all([
          utilizationService.fleet(next),
          utilizationService.daily(next),
        ]);
        setFleet(fleetData);
        setRows(dailyRows ?? []);
      } catch (err) {
        const msg = err instanceof Error ? err.message : String(err);
        setError(msg);
        setFleet(null);
        setRows([]);
      } finally {
        setLoading(false);
      }
    },
    [],
  );

  const reload = useCallback(
    () => reloadWith(range),
    [reloadWith, range],
  );

  useEffect(() => {
    void reloadWith(initial);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return {
    fleet,
    rows,
    loading,
    error,
    reload,
    reloadWith,
    range,
  };
}
