/**
 * WMS Inventory Adjust Note service (PR5-T5.4, Iteration 3.0a).
 *
 * Wraps the backend AdjustController at `/api/v1/wms/inventory-adjusts/*`.
 * State machine:
 *   DRAFT -> (PENDING_APPROVAL ->) APPROVED -> DONE
 *                                \\-> REJECTED
 *                                \\-> CANCELLED.
 * `execute` applies per-line deltaQty to inventory. See `AdjustController.java`.
 */

import { request } from '@/services/request';
import type { ApiEnvelope, PageResult } from '@/services/request';

/** Local alias for backend `R<T>` envelope — see services/wms/inbound.ts. */
type R<T> = ApiEnvelope<T>;

// ---------------------------------------------------------------------------
// Entity / VO types — mirror backend WmsInventoryAdjust + InventoryAdjustVO.
// ---------------------------------------------------------------------------

/** Mirrors `WmsInventoryAdjust` entity (sans lines). */
export interface WmsInventoryAdjust {
  id: number;
  no: string;
  warehouseId: number;
  status: string;
  reason?: string;
  approvedBy?: number;
  approvedAt?: string;
  /** Populated only after a `reject` action (PR5-T5.2). */
  rejectReason?: string;
  remark?: string;
  createdAt?: string;
  updatedAt?: string;
}

/** Mirrors `InventoryAdjustLineVO`. */
export interface WmsInventoryAdjustLine {
  id: number;
  lineNo: number;
  itemId: number;
  locationId: number;
  /** Signed delta — positive = surplus in, negative = loss out. */
  deltaQty: number;
  reasonDetail?: string;
  createdAt?: string;
  updatedAt?: string;
}

/** `InventoryAdjustVO` — note + lines. */
export interface WmsInventoryAdjustWithLines extends WmsInventoryAdjust {
  lines: WmsInventoryAdjustLine[];
}

// ---------------------------------------------------------------------------
// Request DTOs
// ---------------------------------------------------------------------------

/** Mirrors `InventoryAdjustLineDto`. */
export interface InventoryAdjustLineInput {
  itemId: number;
  locationId: number;
  deltaQty: number;
  reasonDetail?: string;
}

/** Mirrors `CreateInventoryAdjustRequest`. */
export interface CreateInventoryAdjustRequest {
  no: string;
  warehouseId: number;
  reason: string;
  remark?: string;
  lines: InventoryAdjustLineInput[];
}

/** Mirrors `UpdateInventoryAdjustRequest`. */
export interface UpdateInventoryAdjustRequest {
  no?: string;
  warehouseId?: number;
  reason?: string;
  remark?: string;
  lines?: InventoryAdjustLineInput[];
}

/** Mirrors `AddInventoryAdjustLineRequest`. */
export interface AddInventoryAdjustLineRequest extends InventoryAdjustLineInput {
  lineNo?: number;
}

/** Mirrors `RejectAdjustRequest`. */
export interface RejectAdjustRequest {
  reason: string;
}

/** Query params for `page` — keyword + warehouseId + status. */
export interface ListAdjustNotesParams {
  pageNum?: number;
  pageSize?: number;
  keyword?: string;
  warehouseId?: number;
  status?: string;
}

// ---------------------------------------------------------------------------
// Service namespace
// ---------------------------------------------------------------------------

export const adjustNoteService = {
  /** GET /wms/inventory-adjusts — paged list. */
  list: (params: ListAdjustNotesParams = {}) =>
    request.get<R<PageResult<WmsInventoryAdjust>>>(
      '/api/v1/wms/inventory-adjusts',
      { params },
    ),

  /** GET /wms/inventory-adjusts/{id} — detail + line items. */
  get: (id: number) =>
    request.get<R<WmsInventoryAdjustWithLines>>(
      `/api/v1/wms/inventory-adjusts/${id}`,
    ),

  /** POST /wms/inventory-adjusts — create with all lines. */
  create: (data: CreateInventoryAdjustRequest) =>
    request.post<R<WmsInventoryAdjust>>('/api/v1/wms/inventory-adjusts', data),

  /** PUT /wms/inventory-adjusts/{id} — update head + optional line replacement. */
  update: (id: number, data: UpdateInventoryAdjustRequest) =>
    request.put<R<WmsInventoryAdjust>>(
      `/api/v1/wms/inventory-adjusts/${id}`,
      data,
    ),

  /** DELETE /wms/inventory-adjusts/{id} — soft delete + cascade lines. */
  delete: (id: number) =>
    request.delete<R<void>>(`/api/v1/wms/inventory-adjusts/${id}`),

  /** POST /wms/inventory-adjusts/{id}/lines — append a single line. */
  addLine: (id: number, data: AddInventoryAdjustLineRequest) =>
    request.post<R<WmsInventoryAdjustLine>>(
      `/api/v1/wms/inventory-adjusts/${id}/lines`,
      data,
    ),

  /** DELETE /wms/inventory-adjusts/{id}/lines/{lid} — remove a line. */
  removeLine: (id: number, lineId: number) =>
    request.delete<R<void>>(
      `/api/v1/wms/inventory-adjusts/${id}/lines/${lineId}`,
    ),

  /** POST /wms/inventory-adjusts/{id}/submit — threshold-routed. */
  submit: (id: number) =>
    request.post<R<WmsInventoryAdjust>>(
      `/api/v1/wms/inventory-adjusts/${id}/submit`,
      {},
    ),

  /** POST /wms/inventory-adjusts/{id}/approve — PENDING_APPROVAL -> APPROVED. */
  approve: (id: number) =>
    request.post<R<WmsInventoryAdjust>>(
      `/api/v1/wms/inventory-adjusts/${id}/approve`,
      {},
    ),

  /** POST /wms/inventory-adjusts/{id}/reject — PENDING_APPROVAL -> REJECTED. */
  reject: (id: number, data: RejectAdjustRequest) =>
    request.post<R<WmsInventoryAdjust>>(
      `/api/v1/wms/inventory-adjusts/${id}/reject`,
      data,
    ),

  /** POST /wms/inventory-adjusts/{id}/execute — APPROVED -> DONE. */
  execute: (id: number) =>
    request.post<R<WmsInventoryAdjust>>(
      `/api/v1/wms/inventory-adjusts/${id}/execute`,
      {},
    ),

  /** POST /wms/inventory-adjusts/{id}/cancel — non-terminal -> CANCELLED. */
  cancel: (id: number) =>
    request.post<R<WmsInventoryAdjust>>(
      `/api/v1/wms/inventory-adjusts/${id}/cancel`,
      {},
    ),
};