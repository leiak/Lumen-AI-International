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
};

export interface StatusTagProps {
  value: Status;
  /** Override the displayed text. */
  text?: string;
}

export function StatusTag({ value, text }: StatusTagProps) {
  const key = String(value).toUpperCase();
  const color = COLOR_MAP[key] ?? 'default';
  const label = text ?? TEXT_MAP[key] ?? String(value);
  return <Tag color={color}>{label}</Tag>;
}