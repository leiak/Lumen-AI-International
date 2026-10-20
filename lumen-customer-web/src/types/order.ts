/**
 * 4.5B.3 — Order TypeScript types.
 *
 * Mirrors backend DTOs in
 *   lumen-parent/lumen-customer/src/main/java/com/lumen/customer/dto/
 *   lumen-parent/lumen-finance/src/main/java/com/lumen/finance/dto/EstimateResult.java
 *   lumen-parent/lumen-dispatch/src/main/java/com/lumen/dispatch/dto/PortalOrderRequest.java
 *
 * Field names use camelCase to match Jackson's default property naming strategy.
 */

/* ---------- Estimate (preview) ---------- */

/** POST /api/customer/order/preview  response. */
export interface PortalEstimateResult {
  amount: number | null;
  source: 'RULE' | 'OVERRIDE' | 'NONE';
  ruleId: number | null;
  overrideId: number | null;
  currency: string;
  breakdown: string;
}

/* ---------- Order list ---------- */

/** Order list page row — GET /api/customer/order. */
export interface PortalOrderListItem {
  doId: number;
  doNo: string;
  status: string;
  originAddress: string | null;
  destAddress: string | null;
  createdAt: string | null;
  signedAt: string | null;
  plannedPickupWindowStart: string | null;
  plannedPickupWindowEnd: string | null;
}

export interface PortalOrderListQuery {
  doNo?: string;
  status?: string;
  page?: number;
  size?: number;
}

export interface PortalOrderListData {
  records: PortalOrderListItem[];
  total: number;
  page: number;
  size: number;
}

/* ---------- Order detail ---------- */

/** Single timeline entry inside PortalOrderDetail.timeline. */
export interface PortalOrderTimelineEvent {
  status: string;
  eventName: string | null;
  operator: string | null;
  occurredAt: string | null;
  remark: string | null;
}

/** Order detail — GET /api/customer/order/{doId}. */
export interface PortalOrderDetail extends PortalOrderListItem {
  canCancel: boolean;
  timeline: PortalOrderTimelineEvent[];
}

/* ---------- Create / Cancel requests ---------- */

/** POST /api/customer/order  body. */
export interface PortalCreateOrderRequest {
  warehouseId: number;
  shipperAddress: string;
  consigneeAddress: string;
  totalWeightKg: number;
  totalVolumeM3: number;
  stopCount: number;
  plannedPickupWindowStart: string;  // ISO datetime, e.g. "2026-10-20T14:00:00"
  plannedPickupWindowEnd: string | null;
  remark: string | null;
}

/** POST /api/customer/order/{doId}/cancel  body. */
export interface PortalCancelOrderRequest {
  reason: string;
}