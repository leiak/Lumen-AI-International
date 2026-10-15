/**
 * CS 业务时间 service (Iteration 4.2 PR2).
 *
 * Wraps `BusinessCalendarController` at `/api/v1/cs/calendar`.
 * 每条记录是 (weekday, startTime, endTime)，表示一个 tenant 在某星期
 * 几的工作时段；一天可有多段（午休拆分）.
 */

import { request } from '@/services/request';
import type { ApiEnvelope } from '@/services/request';
import { asData } from './_util';

type R<T> = ApiEnvelope<T>;

export interface BusinessCalendar {
  id: number;
  tenantId: number;
  weekday: number;        // 1..7 (Mon..Sun)
  startTime: string;      // "HH:mm:ss"
  endTime: string;
  createdAt?: string;
  updatedAt?: string;
}

export interface CreateBusinessCalendarRequest {
  weekday: number;
  startTime: string;
  endTime: string;
}

export interface UpdateBusinessCalendarRequest {
  startTime: string;
  endTime: string;
}

export interface BusinessCalendarListResponse {
  items: BusinessCalendar[];
  total: number;
}

export const businessCalendarService = {
  list: (): Promise<BusinessCalendarListResponse> =>
    asData<BusinessCalendarListResponse>(
      request.get<R<BusinessCalendarListResponse>>('/api/v1/cs/calendar'),
    ),

  get: (id: number): Promise<BusinessCalendar> =>
    asData<BusinessCalendar>(request.get<R<BusinessCalendar>>(`/api/v1/cs/calendar/${id}`)),

  create: (data: CreateBusinessCalendarRequest): Promise<BusinessCalendar> =>
    asData<BusinessCalendar>(request.post<R<BusinessCalendar>>('/api/v1/cs/calendar', data)),

  update: (id: number, data: UpdateBusinessCalendarRequest): Promise<BusinessCalendar> =>
    asData<BusinessCalendar>(request.put<R<BusinessCalendar>>(`/api/v1/cs/calendar/${id}`, data)),

  delete: (id: number): Promise<void> =>
    asData<void>(request.delete<R<void>>(`/api/v1/cs/calendar/${id}`)),
};