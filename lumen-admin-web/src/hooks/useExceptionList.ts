/**
 * useExceptionList — fetch a page of DeliveryException work orders (Iteration 4.1 PR6).
 *
 * Thin React wrapper over `deliveryExceptionService.page`.
 *
 * Usage:
 *   const { rows, loading, reload, reloadWith } = useExceptionList();
 *
 * `reloadWith({ status: 'OPEN' })` re-fetches with the given filter;
 * `reload()` re-fetches with the most recent params.
 */

import { useCallback, useEffect, useState } from 'react';
import {
  deliveryExceptionService,
  type WmsDeliveryException,
} from '@/services/dispatch/exception';

export interface ExceptionListParams {
  current?: number;
  pageNum?: number;
  size?: number;
  pageSize?: number;
  status?: string;
  orderId?: number;
}

export interface UseExceptionListResult {
  rows: WmsDeliveryException[];
  loading: boolean;
  error: string | null;
  reload: () => Promise<void>;
  reloadWith: (params: ExceptionListParams) => Promise<void>;
  params: ExceptionListParams;
}

const DEFAULT_PARAMS: ExceptionListParams = { current: 1, size: 20 };

export function useExceptionList(
  initial: ExceptionListParams = DEFAULT_PARAMS,
): UseExceptionListResult {
  const [rows, setRows] = useState<WmsDeliveryException[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [params, setParams] = useState<ExceptionListParams>(initial);

  const reloadWith = useCallback(
    async (next: ExceptionListParams): Promise<void> => {
      setLoading(true);
      setError(null);
      setParams(next);
      try {
        const res = await deliveryExceptionService.page(next);
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
  };
}
