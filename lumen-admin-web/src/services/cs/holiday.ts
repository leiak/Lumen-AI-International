/**
 * CS 节假日 service (Iteration 4.2 PR2).
 *
 * Wraps `HolidayController` at `/api/v1/cs/holidays`.
 * 节假日用于 SlaCalculator 计算业务时间 — 节假日整体跳过.
 */

import { request } from '@/services/request';
import type { ApiEnvelope } from '@/services/request';
import { asData } from './_util';

type R<T> = ApiEnvelope<T>;

export interface Holiday {
  id: number;
  tenantId: number;
  holidayDate: string;     // ISO date "YYYY-MM-DD"
  description?: string | null;
  createdAt?: string;
  updatedAt?: string;
}

export interface CreateHolidayRequest {
  holidayDate: string;
  description?: string;
}

export interface UpdateHolidayRequest {
  description?: string;
}

export interface HolidayListResponse {
  items: Holiday[];
  total: number;
}

export const holidayService = {
  list: (): Promise<HolidayListResponse> =>
    asData<HolidayListResponse>(
      request.get<R<HolidayListResponse>>('/api/v1/cs/holidays'),
    ),

  get: (id: number): Promise<Holiday> =>
    asData<Holiday>(request.get<R<Holiday>>(`/api/v1/cs/holidays/${id}`)),

  create: (data: CreateHolidayRequest): Promise<Holiday> =>
    asData<Holiday>(request.post<R<Holiday>>('/api/v1/cs/holidays', data)),

  update: (id: number, data: UpdateHolidayRequest): Promise<Holiday> =>
    asData<Holiday>(request.put<R<Holiday>>(`/api/v1/cs/holidays/${id}`, data)),

  delete: (id: number): Promise<void> =>
    asData<void>(request.delete<R<void>>(`/api/v1/cs/holidays/${id}`)),
};