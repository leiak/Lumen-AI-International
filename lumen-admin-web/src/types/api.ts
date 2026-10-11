/**
 * Lumen Admin — shared API types.
 *
 * Mirrors the backend DTO/entity shape so that page code stays strongly typed
 * instead of relying on `any` casts. Field names match the Java entities
 * (camelCase) and the `BaseEntity` superclass fields.
 *
 * PageResult<T> is re-exported from services/request.ts — keep the import path
 * stable so axios interceptor typing remains consistent.
 */

import type { PageResult } from '@/services/request';

// ---------------------------------------------------------------------------
// RBAC
// ---------------------------------------------------------------------------

export interface SysUser {
  id: number;
  username: string;
  realName?: string;
  email?: string;
  phone?: string;
  status: 0 | 1;
  lastLoginAt?: string;
  createdAt?: string;
  updatedAt?: string;
}

export type DataScope = 'ALL' | 'DEPT' | 'DEPT_AND_SUB' | 'SELF' | 'CUSTOM';

export interface SysRole {
  id: number;
  code: string;
  name: string;
  description?: string;
  dataScope?: DataScope;
  status: 0 | 1;
  createdAt?: string;
}

export type PermissionType = 'MENU' | 'BUTTON' | 'API';

export interface SysPermission {
  id: number;
  parentId?: number;
  type: PermissionType;
  code: string;
  name: string;
  path?: string;
  sortOrder?: number;
  status: 0 | 1;
}

/** Permission matrix — Map<roleCode, List<permissionCode>>. */
export type PermissionMatrix = Record<string, string[]>;

// ---------------------------------------------------------------------------
// Master data
// ---------------------------------------------------------------------------

export interface SysDict {
  id: number;
  code: string;
  name: string;
  description?: string;
  createdAt?: string;
}

/**
 * SysDictItem — mirrors {@code com.lumen.masterdata.entity.SysDictItem}.
 *
 * <p>{@code code} is the business-facing item identifier (per-dict unique);
 * {@code label} is the human-readable text shown in selects / tables; both
 * are kept distinct from the surrogate numeric {@code id}. {@code sortOrder}
 * controls rendering order; {@code status} is {@code 1=启用 / 0=禁用}.
 */
export interface SysDictItem {
  id: number;
  dictId?: number;
  code: string;
  label: string;
  sortOrder?: number;
  status: 0 | 1;
  createdAt?: string;
  updatedAt?: string;
}

// ---------------------------------------------------------------------------
// Numbering
// ---------------------------------------------------------------------------

export type ResetPolicy = 'DAY' | 'MONTH' | 'YEAR' | 'NEVER';

export interface SysNumberRule {
  id: number;
  code: string;
  prefix?: string;
  dateFormat?: string;
  seqLength?: number;
  resetPolicy: ResetPolicy;
  currentValue?: number;
  description?: string;
}

// ---------------------------------------------------------------------------
// Notification
// ---------------------------------------------------------------------------

export type NotificationChannel = 'EMAIL' | 'SMS' | 'IM';

export interface NotificationTemplate {
  id: number;
  code: string;
  channel: NotificationChannel;
  subject?: string;
  content?: string;
  vars?: string;
  status: 0 | 1;
  createdAt?: string;
}

// ---------------------------------------------------------------------------
// Audit log — no backend controller yet (see AuditLog page comment).
// ---------------------------------------------------------------------------

export interface SysAuditLog {
  id: number;
  traceId?: string;
  userId?: number;
  username?: string;
  action?: string;
  resource?: string;
  uri?: string;
  status: 0 | 1;
  costMs?: number;
  createdAt?: string;
}

// ---------------------------------------------------------------------------
// WMS master data — mirrors lumen-parent/lumen-wms/.../entity/*.java.
// Status columns are stored as the enum's `code` string
// (e.g. WmsLocationStatus.ACTIVE.code === "ACTIVE"), so the frontend types
// them as plain `string` and only normalises at the render boundary.
// ---------------------------------------------------------------------------

export interface WmsWarehouse {
  id: number;
  code: string;
  name: string;
  type?: string;
  status?: string;
  address?: string;
  createdAt?: string;
  updatedAt?: string;
}

export interface WmsLocation {
  id: number;
  warehouseId: number;
  code: string;
  zone?: string;
  capacity?: number;
  status?: string;
  createdAt?: string;
  updatedAt?: string;
}

export interface WmsItem {
  id: number;
  sku: string;
  name: string;
  uom?: string;
  barcode?: string;
  hazardClass?: string;
  unitCost?: number;
  createdAt?: string;
  updatedAt?: string;
}

export interface WmsCustomer {
  id: number;
  code: string;
  name: string;
  contact?: string;
  phone?: string;
  address?: string;
  createdAt?: string;
  updatedAt?: string;
}

/**
 * WmsInventory — mirrors {@code com.lumen.wms.entity.WmsInventory}.
 *
 * <p>{@code qty} / {@code lockedQty} are {@code BigDecimal} on the backend;
 * the JSON wire format emits them as either number or string depending on
 * Jackson config, so call sites coerce via {@code Number(...)} or rely on
 * the column formatter. {@code availableQty} is intentionally not part of
 * the entity — it's derived as {@code qty − lockedQty} wherever needed.
 */
export interface WmsInventory {
  id: number;
  warehouseId: number;
  locationId: number;
  itemId: number;
  qty: number | string;
  lockedQty: number | string;
  lastMovementAt?: string;
  createdAt?: string;
  updatedAt?: string;
}

export type { PageResult };
