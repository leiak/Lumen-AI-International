/**
 * 货运委托单状态标签 (Iteration 4.6).
 *
 * <p><b>不复用 `components/columns/StatusTag.tsx`</b> —— 那个通用组件的映射表里:
 * <ul>
 *   <li>`CLOSED` 映射成「已关闭」(配送异常工单的语义), 而委托单的 `CLOSED` 是「已归档」;</li>
 *   <li>`DELIVERED` **根本不在映射表里** → 落到默认值, 显示「未知」+ 灰色标签;</li>
 *   <li>`CANCELLED` 恰好两边一致 (都是「已取消」)。</li>
 * </ul>
 * 委托单有 9 个状态, 其中 8 个在通用映射表里对不上或缺失 —— 复用会显示错标签。
 */

import { Tag } from 'antd';

interface Props {
  value?: string | null;
}

/** 委托单状态 → 颜色。 */
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

/** 委托单状态 → 中文标签。 */
const LABELS: Record<string, string> = {
  DRAFT: '草稿',
  PENDING_CONFIRM: '待确认',
  CONFIRMED: '已确认',
  IN_OPERATION: '操作中',
  DELIVERED: '已交付',
  SETTLED: '已结算',
  CLOSED: '已归档',
  CANCELLED: '已取消',
  VOID: '已作废',
};

export function OrderStatusTag({ value }: Props) {
  const key = (value ?? '').toUpperCase();
  // 注意: `key` 是空串而不是 null —— `??` 不会把空串兜住, 得显式判空。
  return <Tag color={COLORS[key] ?? 'default'}>{LABELS[key] ?? (key || '未知')}</Tag>;
}

export default OrderStatusTag;
