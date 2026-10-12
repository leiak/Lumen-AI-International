/**
 * VehicleUtilization service (Iteration 4.1 PR5 / PR6 finalization).
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

import { asData } from './_util';

/** Local alias for backend `R<T>` envelope — see services/wms/inbound.ts. */
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

/** Query params for `daily` — date range + optional per-vehicle filter. */
export interface DailyQuery {
  dateStart: string;       // ISO yyyy-MM-dd
  dateEnd: string;         // ISO yyyy-MM-dd
  vehicleId?: number;
}

// ---------------------------------------------------------------------------
// Service namespace
// ---------------------------------------------------------------------------

export const utilizationService = {
  /** GET /dispatch/utilization/daily — per-(date, vehicle) breakdown. */
  daily: (q: DailyQuery): Promise<DailyUtilizationRow[]> =>
    asData<DailyUtilizationRow[]>(
      request.get<R<DailyUtilizationRow[]>>(
        '/api/v1/dispatch/utilization/daily',
        { params: q },
      ),
    ),

  /** GET /dispatch/utilization/fleet — KPI totals + dailyBreakdown. */
  fleet: (
    q: { dateStart: string; dateEnd: string },
  ): Promise<FleetUtilizationResponse> =>
    asData<FleetUtilizationResponse>(
      request.get<R<FleetUtilizationResponse>>(
        '/api/v1/dispatch/utilization/fleet',
        { params: q },
      ),
    ),
};
