/**
 * useDeliveryOrderDetail — fetch one DeliveryOrder + its stops (Iteration 4.1 PR6).
 *
 * Composite hook: returns `{ order, stops, loading, error, reload }`. Stops are
 * loaded alongside the order; if the stops fetch fails (e.g. order has no
 * stops yet) we still surface the order and an empty stops array.
 *
 * Usage:
 *   const { order, stops, loading, reload } = useDeliveryOrderDetail(id);
 */

import { useCallback, useEffect, useState } from 'react';
import {
  deliveryOrderService,
  type WmsDeliveryOrder,
  type WmsDeliveryStop,
} from '@/services/dispatch/deliveryOrder';

export interface UseDeliveryOrderDetailResult {
  order: WmsDeliveryOrder | null;
  stops: WmsDeliveryStop[];
  loading: boolean;
  error: string | null;
  reload: () => Promise<void>;
}

export function useDeliveryOrderDetail(
  id: number | string | null | undefined,
): UseDeliveryOrderDetailResult {
  const [order, setOrder] = useState<WmsDeliveryOrder | null>(null);
  const [stops, setStops] = useState<WmsDeliveryStop[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const reload = useCallback(async (): Promise<void> => {
    if (id == null) return;
    setLoading(true);
    setError(null);
    try {
      const oid = typeof id === 'string' ? Number(id) : id;
      const o = await deliveryOrderService.get(oid);
      setOrder(o);
      try {
        const s = await deliveryOrderService.listStops(oid);
        setStops(s ?? []);
      } catch {
        // Stops are auxiliary; if they fail to load (e.g. order has
        // none yet) we still surface the order. Don't propagate.
        setStops([]);
      }
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err);
      setError(msg);
      setOrder(null);
    } finally {
      setLoading(false);
    }
  }, [id]);

  useEffect(() => {
    void reload();
  }, [reload]);

  return { order, stops, loading, error, reload };
}
