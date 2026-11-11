/**
 * 4.6 — 货运委托单状态标签。
 *
 * <p>不复用 `components/StatusTag.tsx` —— 那个组件的 label 来自 `zhCN.status`
 * (城配单的 8 个状态 + 一个 UNKNOWN 兜底), 其中 `DELIVERED` 是「已签收」,
 * 而委托单的 `DELIVERED` 是「已交付」; `CLOSED` 在委托单里是「已归档」, 而
 * `zhCN.status` 里**根本没有 CLOSED 这个键** —— 复用会显示错标签。
 */

import React from 'react';
import { Tag } from 'antd';
import { zhCN } from '../locale/zh-CN';

interface Props {
  status?: string | null;
}

const COLORS: Record<string, string> = {
  DRAFT: 'default',
  PENDING_CONFIRM: 'gold',
  CONFIRMED: 'blue',
  IN_OPERATION: 'processing',
  DELIVERED: 'cyan',
  SETTLED: 'purple',
  CLOSED: 'default',
  CANCELLED: 'default',
  VOID: 'red',
};

const FreightOrderStatusTag: React.FC<Props> = ({ status }) => {
  const key = (status ?? '').toUpperCase();
  const color = COLORS[key] ?? 'default';
  const label =
    zhCN.freightOrder.status[key as keyof typeof zhCN.freightOrder.status] ?? key;
  return <Tag color={color}>{label}</Tag>;
};

export default FreightOrderStatusTag;
