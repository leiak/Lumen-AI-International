/**
 * StatusTag — color-coded badge for boolean/enum status fields.
 *
 * Wraps antd Tag with the conventional colors:
 *   success (green)  → active / enabled / 1
 *   error (red)      → inactive / disabled / 0
 *   processing (blue)→ pending / in-progress
 *   warning (yellow) → deprecated
 *   default (gray)   → unknown
 *
 * Accepts either a boolean (1/0), an integer (0/1), or a string union
 * ('ACTIVE' | 'INACTIVE' | ...).
 */

import { Tag } from 'antd';
import type { BadgeProps } from 'antd';

type Status = boolean | 0 | 1 | 'ACTIVE' | 'INACTIVE' | 'PENDING' | 'DEPRECATED' | string;

const COLOR_MAP: Record<string, BadgeProps['status']> = {
  true: 'success',
  false: 'default',
  '1': 'success',
  '0': 'default',
  ACTIVE: 'success',
  ENABLED: 'success',
  INACTIVE: 'default',
  DISABLED: 'default',
  PENDING: 'processing',
  PROCESSING: 'processing',
  ERROR: 'error',
  FAILED: 'error',
  DEPRECATED: 'warning',
  WARNING: 'warning',
  // --- Dispatch masterdata (Iteration 4.1 PR3) ---
  // VEHICLE state machine statuses
  IDLE: 'success',         // 空闲 (green)
  DISPATCHED: 'processing', // 已派车 (blue)
  LOADING: 'warning',       // 装载中 (gold)
  ENROUTE: 'processing',    // 在途 (cyan/blue)
  RETURNING: 'warning',     // 回程 (gold)
  OUT: 'default',           // 停用 (gray)
  // Driver / Carrier shared ACTIVE/SUSPENDED already covered above;
  // explicit aliases below for clarity:
  SUSPENDED: 'default',
  // --- DeliveryOrder state machine (Iteration 4.1 PR4) ---
  PENDING_DISPATCH: 'default',  // 待派车 (gray)
  LOADED: 'warning',            // 已装车 (gold)
  ARRIVED: 'processing',        // 已到达 (blue)
  SIGNED: 'success',            // 已签收 (green)
  EXCEPTION: 'error',           // 异常 (red)
  CANCELLED: 'default',         // 已取消 (gray)
  // --- DeliveryException work order (Iteration 4.1 PR4) ---
  OPEN: 'warning',              // 待处理 (orange-ish via warning gold)
  INVESTIGATING: 'processing',  // 调查中 (blue)
  RESOLVED: 'processing',       // 已解决 (blue)
  CLOSED: 'success',            // 已关闭 (green)
  // --- DeliveryStop (Iteration 4.1 PR4) ---
  COMPLETED: 'success',
};

const TEXT_MAP: Record<string, string> = {
  true: '启用',
  false: '禁用',
  '1': '启用',
  '0': '禁用',
  ACTIVE: '启用',
  ENABLED: '启用',
  INACTIVE: '禁用',
  DISABLED: '禁用',
  PENDING: '处理中',
  PROCESSING: '处理中',
  ERROR: '失败',
  FAILED: '失败',
  DEPRECATED: '已废弃',
  WARNING: '警告',
  // --- Dispatch masterdata (Iteration 4.1 PR3) ---
  // VEHICLE state machine statuses
  IDLE: '空闲',
  DISPATCHED: '已派车',
  LOADING: '装载中',
  ENROUTE: '在途',
  RETURNING: '返程中',
  OUT: '停用',
  // Driver / Carrier
  SUSPENDED: '停用',
  // --- DeliveryOrder state machine (Iteration 4.1 PR4) ---
  PENDING_DISPATCH: '待派车',
  LOADED: '已装车',
  ARRIVED: '已到达',
  SIGNED: '已签收',
  EXCEPTION: '异常',
  CANCELLED: '已取消',
  // --- DeliveryException work order (Iteration 4.1 PR4) ---
  OPEN: '待处理',
  INVESTIGATING: '调查中',
  RESOLVED: '已解决',
  CLOSED: '已关闭',
  // --- DeliveryStop (Iteration 4.1 PR4) ---
  COMPLETED: '已完成',
};

export interface StatusTagProps {
  value: Status;
  /** Override the displayed text. */
  text?: string;
}

export function StatusTag({ value, text }: StatusTagProps) {
  // Handle boolean explicitly: String(true).toUpperCase() === "TRUE", but
  // the lookup maps expect lowercase "true"/"false" — so without this branch
  // booleans silently fall through to the default color and raw text.
  if (typeof value === 'boolean') {
    const color = value ? 'success' : 'default';
    return <Tag color={color}>{text ?? (value ? '启用' : '禁用')}</Tag>;
  }
  const key = String(value).toUpperCase();
  const color = COLOR_MAP[key] ?? 'default';
  const label = text ?? TEXT_MAP[key] ?? '未知';
  return <Tag color={color}>{label}</Tag>;
}