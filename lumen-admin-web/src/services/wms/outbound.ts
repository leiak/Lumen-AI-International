/**
 * WMS Outbound Note service (PR5-T5.4, Iteration 3.0a).
 *
 * Wraps the backend OutboundController at `/api/v1/wms/outbound-notes/*`.
 * State machine:
 *   DRAFT -> (PENDING_APPROVAL ->) PICKING -> REVIEW -> SHIPPED -> DONE.
 * `submit` locks inventory; `complete` flushes locked qty to consumed.
 * See `lumen-parent/lumen-wms/.../controller/OutboundController.java`.
 */

import { request } from '@/services/request';
import type { ApiEnvelope, PageResult } from '@/services/request';

/** Local alias for backend `R<T>` envelope — see services/wms/inbound.ts. */
type R<T> = ApiEnvelope<T>;

// ---------------------------------------------------------------------------
// Entity / VO types — mirror backend WmsOutboundNote + OutboundNoteVO.
// ---------------------------------------------------------------------------

/** Mirrors `WmsOutboundNote` entity (sans lines). */
export interface WmsOutboundNote {
  id: number;
  no: string;
  customerId: number;
  warehouseId: number;
  status: string;
  carrier?: string;
  trackingNo?: string;
  requestedAt?: string;
  shippedAt?: string;
  remark?: string;
  createdAt?: string;
  updatedAt?: string;
}

/** Mirrors `OutboundLineVO`. */
export interface WmsOutboundLine {
  id: number;
  lineNo: number;
  itemId: number;
  qty: number;
  pickedQty?: number;
  sourceLocationId: number;
  createdAt?: string;
  updatedAt?: string;
}

/** `OutboundNoteVO` — note + lines. */
export interface WmsOutboundNoteWithLines extends WmsOutboundNote {
  lines: WmsOutboundLine[];
}

// ---------------------------------------------------------------------------
// Request DTOs — mirror backend `*Request.java` payload shapes.
// ---------------------------------------------------------------------------

/** Mirrors `OutboundLineDto` — each line MUST have `sourceLocationId`. */
export interface OutboundLineInput {
  itemId: number;
  qty: number;
  sourceLocationId: number;
}

/** Mirrors `CreateOutboundNoteRequest`. */
export interface CreateOutboundNoteRequest {
  no: string;
  customerId: number;
  warehouseId: number;
  requestedAt?: string;
  carrier?: string;
  trackingNo?: string;
  remark?: string;
  lines: OutboundLineInput[];
}

/** Mirrors `UpdateOutboundNoteRequest` — no/warehouseId/customerId immutable. */
export interface UpdateOutboundNoteRequest {
  carrier?: string;
  trackingNo?: string;
  remark?: string;
  lines?: OutboundLineInput[];
}

/** Mirrors `AddOutboundLineRequest`. */
export interface AddOutboundLineRequest extends OutboundLineInput {
  lineNo?: number;
}

/** Mirrors `PickOutboundLineRequest`. */
export interface PickOutboundLineRequest {
  qty: number;
}

/** Mirrors `ShipOutboundRequest`. */
export interface ShipOutboundRequest {
  carrier: string;
  trackingNo: string;
}

/** Query params for `page` — keyword + status filters. */
export interface ListOutboundNotesParams {
  pageNum?: number;
  pageSize?: number;
  keyword?: string;
  status?: string;
}

// ---------------------------------------------------------------------------
// Service namespace
// ---------------------------------------------------------------------------

export const outboundNoteService = {
  /** GET /wms/outbound-notes — paged list. */
  list: (params: ListOutboundNotesParams = {}) =>
    request.get<R<PageResult<WmsOutboundNote>>>('/api/v1/wms/outbound-notes', {
      params,
    }),

  /** GET /wms/outbound-notes/{id} — detail + line items. */
  get: (id: number) =>
    request.get<R<WmsOutboundNoteWithLines>>(`/api/v1/wms/outbound-notes/${id}`),

  /** POST /wms/outbound-notes — create with all lines. */
  create: (data: CreateOutboundNoteRequest) =>
    request.post<R<WmsOutboundNote>>('/api/v1/wms/outbound-notes', data),

  /** PUT /wms/outbound-notes/{id} — carrier/trackingNo/remark + lines. */
  update: (id: number, data: UpdateOutboundNoteRequest) =>
    request.put<R<WmsOutboundNote>>(`/api/v1/wms/outbound-notes/${id}`, data),

  /** DELETE /wms/outbound-notes/{id} — soft delete + cascade lines. */
  delete: (id: number) =>
    request.delete<R<void>>(`/api/v1/wms/outbound-notes/${id}`),

  /** POST /wms/outbound-notes/{id}/lines — append a single line. */
  addLine: (id: number, data: AddOutboundLineRequest) =>
    request.post<R<WmsOutboundLine>>(`/api/v1/wms/outbound-notes/${id}/lines`, data),

  /** DELETE /wms/outbound-notes/{id}/lines/{lid} — remove a line. */
  removeLine: (id: number, lineId: number) =>
    request.delete<R<void>>(`/api/v1/wms/outbound-notes/${id}/lines/${lineId}`),

  /** POST /wms/outbound-notes/{id}/submit — threshold-routed + lock inventory. */
  submit: (id: number) =>
    request.post<R<WmsOutboundNote>>(`/api/v1/wms/outbound-notes/${id}/submit`, {}),

  /** POST /wms/outbound-notes/{id}/approve — PENDING_APPROVAL -> PICKING. */
  approve: (id: number) =>
    request.post<R<void>>(`/api/v1/wms/outbound-notes/${id}/approve`, {}),

  /** POST /wms/outbound-notes/{id}/lines/{lid}/pick — increment pickedQty. */
  pickLine: (id: number, lineId: number, data: PickOutboundLineRequest) =>
    request.post<R<WmsOutboundLine>>(
      `/api/v1/wms/outbound-notes/${id}/lines/${lineId}/pick`,
      data,
    ),

  /** POST /wms/outbound-notes/{id}/ship — REVIEW -> SHIPPED + carrier/tracking. */
  ship: (id: number, data: ShipOutboundRequest) =>
    request.post<R<WmsOutboundNote>>(`/api/v1/wms/outbound-notes/${id}/ship`, data),

  /** POST /wms/outbound-notes/{id}/complete — SHIPPED -> DONE + flushLock. */
  complete: (id: number) =>
    request.post<R<WmsOutboundNote>>(`/api/v1/wms/outbound-notes/${id}/complete`, {}),

  /** POST /wms/outbound-notes/{id}/cancel — non-terminal -> CANCELLED. */
  cancel: (id: number) =>
    request.post<R<WmsOutboundNote>>(`/api/v1/wms/outbound-notes/${id}/cancel`, {}),
};