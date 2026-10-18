/**
 * 4.5A.4-T4.3 — Colored Antd Tag for a delivery-order status string.
 *
 * Backend status codes mirror dispatch state machine (CD2 / §8.5 of the plan):
 *   CREATED, PENDING_DISPATCH, DISPATCHED, IN_TRANSIT, ARRIVED,
 *   DELIVERED, EXCEPTION, CANCELLED.
 *
 * Unknown / null status falls back to a neutral grey tag.
 */

import React from 'react';
import { Tag } from 'antd';
import { zhCN } from '../locale/zh-CN';

interface Props {
  status?: string | null;
}

const COLORS: Record<string, string> = {
  CREATED: 'default',
  PENDING_DISPATCH: 'gold',
  DISPATCHED: 'blue',
  IN_TRANSIT: 'processing',
  ARRIVED: 'cyan',
  DELIVERED: 'success',
  EXCEPTION: 'error',
  CANCELLED: 'magenta',
};

const LABELS: Record<string, string> = zhCN.status;

const StatusTag: React.FC<Props> = ({ status }) => {
  const code = (status ?? 'UNKNOWN').toUpperCase();
  const color = COLORS[code] ?? 'default';
  const label =
    LABELS[code as keyof typeof LABELS] ?? code ?? LABELS.UNKNOWN;

  return <Tag color={color}>{label}</Tag>;
};

export default StatusTag;
