/**
 * WMS Inbound Note service (PR5-T5.4, Iteration 3.0a).
 *
 * Wraps the backend InboundController at `/api/v1/wms/inbound-notes/*`.
 * State machine: DRAFT -> (PENDING_APPROVAL ->) RECEIVING -> PUTAWAY -> DONE.
 * See `lumen-parent/lumen-wms/.../controller/InboundController.java`.
 *
 * Every method delegates to the shared `request` axios instance so the
 * 401-refresh interceptor + envelope unwrapping apply uniformly. Service
 * consumers MUST NOT import axios directly — keep the indirection that lets us
 * swap transports later.
 */

import { request } from '@/services/request';
import type { ApiEnvelope, PageResult } from '@/services/request';

/**
 * Local alias for the backend `R<T>` envelope. The request interceptor
 * unwraps the envelope so consumers see only `T`; this type is here so
 * `request.get<R<PageResult<WmsInboundNote>>>` matches the controller's
 * declared return type.
 */
type R<T> = ApiEnvelope<T>;

// ---------------------------------------------------------------------------
// Entity / VO types — mirror backend WmsInboundNote + InboundNoteVO.
// ---------------------------------------------------------------------------

/** Mirrors `WmsInboundNote` entity (sans lines). */
export interface WmsInboundNote {
  id: number;
  no: string;
  customerId: number;
  warehouseId: number;
  status: string;
  arrivedAt?: string;
  completedAt?: string;
  remark?: string;
  createdAt?: string;
  updatedAt?: string;
}

/** Mirrors `InboundLineVO`. */
export interface WmsInboundLine {
  id: number;
  lineNo: number;
  itemId: number;
  qty: number;
  receivedQty?: number;
  putawayQty?: number;
  putawayLocationId?: number | null;
  createdAt?: string;
  updatedAt?: string;
}

/** `InboundNoteVO` — note + lines (returned by `get`). */
export interface WmsInboundNoteWithLines extends WmsInboundNote {
  lines: WmsInboundLine[];
}

// ---------------------------------------------------------------------------
// Request DTOs — mirror backend `*Request.java` payload shapes.
// ---------------------------------------------------------------------------

/** Mirrors `InboundLineDto` — line embedded in create/update. */
export interface InboundLineInput {
  itemId: number;
  qty: number;
  putawayLocationId?: number;
}

/** Mirrors `CreateInboundNoteRequest`. */
export interface CreateInboundNoteRequest {
  no: string;
  customerId: number;
  warehouseId: number;
  remark?: string;
  lines: InboundLineInput[];
}

/** Mirrors `UpdateInboundNoteRequest` — `lines: null` means "don't touch". */
export interface UpdateInboundNoteRequest {
  no?: string;
  customerId?: number;
  warehouseId?: number;
  remark?: string;
  lines?: InboundLineInput[];
}

/** Mirrors `AddInboundLineRequest`. */
export interface AddInboundLineRequest extends InboundLineInput {
  lineNo?: number;
}

/** Mirrors `ReceiveInboundLineRequest`. */
export interface ReceiveInboundLineRequest {
  qty: number;
}

/** Mirrors `PutawayInboundLineRequest`. */
export interface PutawayInboundLineRequest {
  qty: number;
  locationId: number;
}

/** Query params for `page` — keyword + status filters per backend. */
export interface ListInboundNotesParams {
  pageNum?: number;
  pageSize?: number;
  keyword?: string;
  status?: string;
}

// ---------------------------------------------------------------------------
// Service namespace
// ---------------------------------------------------------------------------

export const inboundNoteService = {
  /** GET /wms/inbound-notes — paged list (keyword + status filters). */
  list: (params: ListInboundNotesParams = {}) =>
    request.get<R<PageResult<WmsInboundNote>>>('/api/v1/wms/inbound-notes', {
      params,
    }),

  /** GET /wms/inbound-notes/{id} — detail + line items. */
  get: (id: number) =>
    request.get<R<WmsInboundNoteWithLines>>(`/api/v1/wms/inbound-notes/${id}`),

  /** POST /wms/inbound-notes — create with all lines. */
  create: (data: CreateInboundNoteRequest) =>
    request.post<R<WmsInboundNote>>('/api/v1/wms/inbound-notes', data),

  /** PUT /wms/inbound-notes/{id} — update head + optional line replacement. */
  update: (id: number, data: UpdateInboundNoteRequest) =>
    request.put<R<WmsInboundNote>>(`/api/v1/wms/inbound-notes/${id}`, data),

  /** DELETE /wms/inbound-notes/{id} — soft delete + cascade lines. */
  delete: (id: number) =>
    request.delete<R<void>>(`/api/v1/wms/inbound-notes/${id}`),

  /** POST /wms/inbound-notes/{id}/lines — append a single line. */
  addLine: (id: number, data: AddInboundLineRequest) =>
    request.post<R<WmsInboundLine>>(`/api/v1/wms/inbound-notes/${id}/lines`, data),

  /** DELETE /wms/inbound-notes/{id}/lines/{lid} — remove a line. */
  removeLine: (id: number, lineId: number) =>
    request.delete<R<void>>(`/api/v1/wms/inbound-notes/${id}/lines/${lineId}`),

  /** POST /wms/inbound-notes/{id}/submit — threshold-routed submit. */
  submit: (id: number) =>
    request.post<R<WmsInboundNote>>(`/api/v1/wms/inbound-notes/${id}/submit`, {}),

  /** POST /wms/inbound-notes/{id}/approve — PENDING_APPROVAL -> RECEIVING. */
  approve: (id: number) =>
    request.post<R<void>>(`/api/v1/wms/inbound-notes/${id}/approve`, {}),

  /** POST /wms/inbound-notes/{id}/cancel — any non-terminal -> CANCELLED. */
  cancel: (id: number) =>
    request.post<R<void>>(`/api/v1/wms/inbound-notes/${id}/cancel`, {}),

  /** POST /wms/inbound-notes/{id}/lines/{lid}/receive — increment receivedQty. */
  receiveLine: (id: number, lineId: number, data: ReceiveInboundLineRequest) =>
    request.post<R<WmsInboundLine>>(
      `/api/v1/wms/inbound-notes/${id}/lines/${lineId}/receive`,
      data,
    ),

  /** POST /wms/inbound-notes/{id}/lines/{lid}/putaway — increment putawayQty. */
  putawayLine: (id: number, lineId: number, data: PutawayInboundLineRequest) =>
    request.post<R<WmsInboundLine>>(
      `/api/v1/wms/inbound-notes/${id}/lines/${lineId}/putaway`,
      data,
    ),
};