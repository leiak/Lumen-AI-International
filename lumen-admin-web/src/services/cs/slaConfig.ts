/**
 * CS SLA 配置 service (Iteration 4.2 PR2).
 *
 * Wraps `SlaConfigController` at `/api/v1/cs/sla-configs/*`.
 * 配置 (severity, level) → response/resolve minutes + 升级目标 level.
 * 见 `lumen-parent/lumen-cs/.../controller/SlaConfigController.java`.
 */

import { request } from '@/services/request';
import type { ApiEnvelope } from '@/services/request';
import { asData } from './_util';

type R<T> = ApiEnvelope<T>;

export interface SlaConfig {
  id: number;
  tenantId: number;
  severity: 'HIGH' | 'MEDIUM' | 'LOW';
  level: 'L1' | 'L2' | 'L3';
  responseMinutes: number;
  resolveMinutes: number;
  escalateToLevel?: string | null;
  createdAt?: string;
  updatedAt?: string;
}

export interface CreateSlaConfigRequest {
  severity: 'HIGH' | 'MEDIUM' | 'LOW';
  level: 'L1' | 'L2' | 'L3';
  responseMinutes: number;
  resolveMinutes: number;
  escalateToLevel?: string | null;
}

export interface UpdateSlaConfigRequest {
  responseMinutes: number;
  resolveMinutes: number;
  escalateToLevel?: string | null;
}

export interface ListSlaConfigsParams {
  severity?: string;
  level?: string;
  page?: number;
  size?: number;
}

export interface SlaConfigListResponse {
  items: SlaConfig[];
  total: number;
}

export const slaConfigService = {
  list: (params: ListSlaConfigsParams = {}): Promise<SlaConfigListResponse> =>
    asData<SlaConfigListResponse>(
      request.get<R<SlaConfigListResponse>>('/api/v1/cs/sla-configs', { params }),
    ),

  get: (id: number): Promise<SlaConfig> =>
    asData<SlaConfig>(request.get<R<SlaConfig>>(`/api/v1/cs/sla-configs/${id}`)),

  create: (data: CreateSlaConfigRequest): Promise<SlaConfig> =>
    asData<SlaConfig>(request.post<R<SlaConfig>>('/api/v1/cs/sla-configs', data)),

  update: (id: number, data: UpdateSlaConfigRequest): Promise<SlaConfig> =>
    asData<SlaConfig>(request.put<R<SlaConfig>>(`/api/v1/cs/sla-configs/${id}`, data)),

  delete: (id: number): Promise<void> =>
    asData<void>(request.delete<R<void>>(`/api/v1/cs/sla-configs/${id}`)),
};