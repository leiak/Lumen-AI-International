/**
 * CS Exception SLA 视图 service (Iteration 4.2 PR6).
 *
 * Wraps the (planned) backend ExceptionSlaViewController at
 * `/api/v1/cs/exceptions`. Each row is the SLA-perspective view of
 * a `WmsDeliveryException` work order — joining the dispatch row with
 * the configured SLA target minutes, the SLA event history, and the
 * derived `currentLevel` (L1 / L2 / L3).
 *
 * The `escalationStatus` is a client-computable enum
 * (`OK` / `WARNING` / `BREACHED`) derived from
 * `businessMinutesElapsed` vs `resolveTargetMinutes`. We include it
 * here so consumers can use it as a single source of truth rather
 * than re-implementing the threshold.
 *
 * See `lumen-parent/lumen-cs/.../dto/EscalationDtos.java` for the
 * canonical DTO shape.
 */

import { request } from '@/services/request';
import type { ApiEnvelope, PageResult } from '@/services/request';
import { asData } from './_util';

/** Local alias for backend `R<T>` envelope — mirrors sibling CS modules. */
type R<T> = ApiEnvelope<T>;

/** SLA escalation level — mirrors backend `CsConstants.LEVEL_L1/L2/L3`. */
export type ExceptionLevel = 'L1' | 'L2' | 'L3';

/** Severity bucket — mirrors backend `CsConstants.SEVERITY_HIGH/MEDIUM/LOW`. */
export type ExceptionSeverity = 'HIGH' | 'MEDIUM' | 'LOW';

/** Aggregated escalation health — computable client-side. */
export type EscalationStatus = 'OK' | 'WARNING' | 'BREACHED';

/** Subset of `SlaEventDto` exposed on the FE for the history popover. */
export interface ExceptionSlaEvent {
  id: number;
  eventType: string;
  occurredAt: string;
}

/**
 * Mirrors backend `EscalationDtos.ExceptionSlaView`. The field
 * `escalationStatus` is also computable client-side; including it here
 * keeps the wire-shape stable for any future server-side derivation.
 */
export interface ExceptionSlaView {
  exceptionId: number;
  type: string;
  status: string;
  handler: string | null;
  currentLevel: ExceptionLevel;
  severity: ExceptionSeverity;
  businessMinutesElapsed: number;
  resolveTargetMinutes: number;
  escalationStatus: EscalationStatus;
  slaEvents: ExceptionSlaEvent[];
}

/**
 * Compute `EscalationStatus` from elapsed/target minutes — mirrors the
 * thresholds used by `SlaCountdownTag` (≥0.8 = WARNING, ≥1.0 = BREACHED).
 */
export function computeEscalationStatus(
  elapsedMinutes: number,
  targetMinutes: number,
): EscalationStatus {
  if (targetMinutes <= 0) return 'OK';
  const ratio = elapsedMinutes / targetMinutes;
  if (ratio >= 1) return 'BREACHED';
  if (ratio >= 0.8) return 'WARNING';
  return 'OK';
}

/** Query params for `listCsExceptions` — Spring `PageResult` + filters. */
export interface ListCsExceptionsParams {
  current?: number;
  pageNum?: number;
  size?: number;
  pageSize?: number;
  status?: string;
  severity?: ExceptionSeverity;
}

export const csExceptionViewService = {
  /**
   * GET /api/v1/cs/exceptions — paged SLA-perspective view of exceptions.
   *
   * Accepts BOTH the legacy FE shape (`pageNum` / `pageSize`) AND the
   * canonical Spring shape (`current` / `size`). On the wire we always
   * send `current` / `size` regardless of what the caller used.
   */
  list: (params: ListCsExceptionsParams = {}): Promise<PageResult<ExceptionSlaView>> => {
    const wire = {
      current: params.current ?? params.pageNum,
      size: params.size ?? params.pageSize,
      status: params.status,
      severity: params.severity,
    };
    return asData<PageResult<ExceptionSlaView>>(
      request.get<R<PageResult<ExceptionSlaView>>>(
        '/api/v1/cs/exceptions',
        { params: wire },
      ),
    );
  },
};

/** Convenience alias matching the plan's `listCsExceptions` export name. */
export const listCsExceptions = csExceptionViewService.list;