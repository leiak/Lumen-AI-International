/**
 * 4.6 — 货运委托单状态标签。
 *
 * <p>不复用 `components/StatusTag.tsx` —— 那个组件的 label 来自 `zhCN.status`
 * (城配单的 8 个状态 + 一个 UNKNOWN 兜底), 其中 `DELIVERED` 是「已签收」,
 * 而委托单的 `DELIVERED` 是「已交付」; `CLOSED` 在委托单里是「已归档」, 而
 * `zhCN.status` 里**根本没有 CLOSED 这个键** —— 复用会显示错标签。
 *
 * <p>这里**直接引 `zhCN`**, 与本仓其它每一个页面/组件一致
 * (`StatusTag` / `PodPreview` / `PortalLayout` / `pages/**` 全部如此)。
 * **customer-web 的** `locale/en-US.ts` 目前只是**词表占位** —— **customer-web 内**
 * 没有任何文件引它 (admin-web 的 `src/locale/en-US.ts` 已被 `LocaleProvider.tsx` 引用),
 * 真正的 react-i18next 集成留待 4.5B+ (见 `locale/zh-CN.ts` 头部说明)。
 * 在那之前, 单个组件单独接 i18n 只会让口径更乱。
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
