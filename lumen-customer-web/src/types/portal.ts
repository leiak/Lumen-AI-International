/**
 * 4.5A.4-T4.2 — Portal TypeScript types.
 *
 * Mirrors backend record DTOs in
 *   lumen-parent/lumen-customer/src/main/java/com/lumen/customer/dto/
 *
 * Field names use camelCase to match Jackson's default property naming strategy.
 * Nullable fields use `| null` (Java record's nullable return) for stricter typing.
 */

import type { Dayjs } from 'dayjs';

/** Standard backend R<T> envelope. */
export interface ApiResponse<T> {
  code: number;
  message: string;
  data: T;
  traceId?: string;
  timestamp?: number;
}

/* ---------- Auth ---------- */

/** POST /api/auth/portal/login  body. */
export interface PortalLoginRequest {
  username: string;
  password: string;
}

/** One entry in the customer's selection list (multi-tenant / multi-customer users). */
export interface PortalCustomerChoice {
  id: number;
  code: string;
  name: string;
  isPrimary: boolean;
}

/**
 * POST /api/auth/portal/login  AND  /select-customer  response.
 *
 * Note: `choices` is null when the user has only one customer (per backend JavaDoc
 * "null 若单客户"); `activeCustomerId` may be null until the user selects.
 */
export interface PortalLoginResponse {
  token: string;
  activeCustomerId: number | null;
  choices: PortalCustomerChoice[] | null;
  userId: number;
  username: string;
  realName: string;
}

/** GET /api/auth/portal/me  response. */
export interface PortalMeResponse {
  userId: number;
  username: string;
  realName: string;
  activeCustomerId: number;
  activeCustomerName: string;
  allChoices: PortalCustomerChoice[];
}

/* ---------- Track ---------- */

/** Track list page row — GET /api/customer/track. */
export interface PortalTrackListItem {
  doId: number;
  doNo: string;
  status: string;
  originAddress: string | null;
  destAddress: string | null;
  recipientName: string | null;
  recipientPhone: string | null;
  createdAt: string | null;
  signedAt: string | null;
}

/** Single timeline entry inside PortalTrackDetail.timeline. */
export interface PortalTrackEvent {
  status: string;
  eventName: string | null;
  operator: string | null;
  occurredAt: string | null;
  remark: string | null;
}

/** Track detail — GET /api/customer/track/{doId}. */
export interface PortalTrackDetail {
  doId: number;
  doNo: string;
  status: string;
  originAddress: string | null;
  destAddress: string | null;
  recipientName: string | null;
  recipientPhone: string | null;
  weight: number | null;
  volume: number | null;
  createdAt: string | null;
  signedAt: string | null;
  timeline: PortalTrackEvent[];
}

/** Proof of delivery — GET /api/customer/track/{doId}/pod. */
export interface PortalPodResponse {
  doId: number;
  podType: string | null;
  imageUrl: string | null;
  signatureBase64: string | null;
  signerName: string | null;
  signedAt: string | null;
}

/* ---------- Profile ---------- */

/**
 * GET /api/customer/profile  response (4.5A stub — returns customerId + note).
 * 4.5B will replace with full profile DTO.
 */
export interface PortalProfile {
  customerId: number;
  note: string;
}

/** PUT /api/customer/profile  body. */
export interface PortalProfileUpdate {
  phone?: string;
  address?: string;
  password?: string;
}

/* ---------- Helper ---------- */

/** Convenience union for date strings (ISO) or Dayjs in components. */
export type PortalDate = string | Dayjs | null | undefined;
