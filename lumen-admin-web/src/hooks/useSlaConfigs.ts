/**
 * useSlaConfigs — read-only SLA config lookup (Iteration 4.2 PR6).
 *
 * Wraps `slaConfigService.list` for callers that need the SLA configs
 * as a configuration source (e.g. for an EscalationService simulator
 * in the FE, or a read-only audit page). Does not expose mutation —
 * for create / update / delete use `slaConfigService` directly.
 *
 * Usage:
 *   const { data, loading, error } = useSlaConfigs('HIGH', 'L1');
 *
 * Re-fetches whenever either dependency changes. Page is fixed to 1/100
 * — the seed data only has 9 rows; pagination is not exposed here.
 */

import { useEffect, useState } from 'react';
import { slaConfigService, type SlaConfig } from '@/services/cs/slaConfig';

export interface UseSlaConfigsResult {
  data: SlaConfig[];
  loading: boolean;
  error: string | null;
}

export function useSlaConfigs(severity?: string, level?: string): UseSlaConfigsResult {
  const [data, setData] = useState<SlaConfig[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);
    slaConfigService
      .list({ severity, level, page: 1, size: 100 })
      .then((r) => {
        if (cancelled) return;
        setData(r.items ?? []);
      })
      .catch((err: unknown) => {
        if (cancelled) return;
        const msg = err instanceof Error ? err.message : String(err);
        setError(msg);
        setData([]);
      })
      .finally(() => {
        if (cancelled) return;
        setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [severity, level]);

  return { data, loading, error };
}