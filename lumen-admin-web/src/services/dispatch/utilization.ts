/**
 * VehicleUtilization service (Iteration 4.1 PR5).
 *
 * Wraps the backend UtilizationController at
 *   /api/v1/dispatch/utilization/{daily,fleet}.
 *
 * Both endpoints are gated by `dispatch:dashboard:view` perm. The `daily` endpoint
 * returns the per-(date, vehicle) breakdown; the `fleet` endpoint wraps it with
 * KPI totals for the dashboard top cards.
 *
 * Utilization rate (0.0 ~ 1.0) is computed backend-side as
 *   min(total_weight_kg / (vehicle.max_load_kg * 3), 1.0)
 * (see backend `UtilizationDtos` javadoc for the rationale).
 */

import { request } from '@/services/request';
import type { ApiEnvelope } from '@/services/request';

type R<T> = ApiEnvelope<T>;

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

export interface DailyUtilizationRow {
  date: string;            // ISO yyyy-MM-dd
  vehicleId: number;
  plateNo: string;
  dispatchedCount: number;
  totalWeightKg: number;
  totalVolumeM3: number;
  utilizationRate: number; // 0.0 ~ 1.0
}

export interface FleetUtilizationResponse {
  dateStart: string;
  dateEnd: string;
  totalDispatched: number;
  totalWeightKg: number;
  totalVolumeM3: number;
  averageUtilizationRate: number; // 0.0 ~ 1.0
  dailyBreakdown: DailyUtilizationRow[];
}

function unwrap<T>(env: R<T> | null | undefined): T | null {
  return env && typeof env === 'object' && 'data' in env ? (env as { data: T }).data : (env as unknown as T);
}

export interface DailyQuery {
  dateStart: string;       // ISO yyyy-MM-dd
  dateEnd: string;         // ISO yyyy-MM-dd
  vehicleId?: number;
}

export const utilizationService = {
  async daily(q: DailyQuery): Promise<DailyUtilizationRow[]> {
    const env = (await request.get('/dispatch/utilization/daily', { params: q })) as unknown as R<DailyUtilizationRow[]>;
    return unwrap(env) ?? [];
  },

  async fleet(q: { dateStart: string; dateEnd: string }): Promise<FleetUtilizationResponse> {
    const env = (await request.get('/dispatch/utilization/fleet', { params: q })) as unknown as R<FleetUtilizationResponse>;
    const d = unwrap(env);
    if (!d) {
      return {
        dateStart: q.dateStart,
        dateEnd: q.dateEnd,
        totalDispatched: 0,
        totalWeightKg: 0,
        totalVolumeM3: 0,
        averageUtilizationRate: 0,
        dailyBreakdown: [],
      };
    }
    return d;
  },
};
