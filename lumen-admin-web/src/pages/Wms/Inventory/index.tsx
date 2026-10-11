/**
 * Lumen Admin — WMS Inventory ledger view (PR2-T2.3, Iteration 3.0a).
 *
 * Read-only ProTable backed by `GET /api/v1/wms/inventories`. The page is
 * intentionally non-mutating: stock movements are produced internally by
 * InboundService / OutboundService / AdjustService, never by the user.
 *
 * The backend requires exactly one filter (warehouseId OR itemId). The page
 * enforces this by short-circuiting the request handler when both are empty
 * and surfacing a friendly toast — ProTable's SearchConfig (this version)
 * does not expose a submit-time guard hook.
 *
 * `availableQty` is computed client-side as `qty - lockedQty`; the server
 * does not store it because it is derivable and would risk drift.
 */

import type { ActionType, ProColumns } from '@ant-design/pro-components';
import { ProTable } from '@ant-design/pro-components';
import { App } from 'antd';
import { useRef } from 'react';
import { DateTimeColumn } from '@/components/columns/DateTimeColumn';
import { request } from '@/services/request';
import type { PageResult, WmsInventory } from '@/types/api';

function toNumber(v: unknown): number {
  if (typeof v === 'number') return v;
  if (typeof v === 'string') {
    const n = Number(v);
    return Number.isFinite(n) ? n : 0;
  }
  return 0;
}

function formatQty(v: unknown): string {
  return toNumber(v).toLocaleString('zh-CN', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  });
}

export default function WmsInventoryPage() {
  const actionRef = useRef<ActionType>();
  const { message } = App.useApp();

  const columns: ProColumns<WmsInventory>[] = [
    { title: 'ID', dataIndex: 'id', width: 80, hideInSearch: true },
    {
      title: '仓库 ID',
      dataIndex: 'warehouseId',
      width: 120,
      valueType: 'digit',
    },
    {
      title: '库位 ID',
      dataIndex: 'locationId',
      width: 120,
      hideInSearch: true,
    },
    {
      title: '货品 ID',
      dataIndex: 'itemId',
      width: 120,
      valueType: 'digit',
    },
    {
      title: '数量',
      dataIndex: 'qty',
      width: 120,
      hideInSearch: true,
      render: (_, r) => formatQty(r.qty),
    },
    {
      title: '锁定数量',
      dataIndex: 'lockedQty',
      width: 120,
      hideInSearch: true,
      render: (_, r) => formatQty(r.lockedQty),
    },
    {
      title: '可用数量',
      dataIndex: 'availableQty',
      width: 120,
      hideInSearch: true,
      render: (_, r) => formatQty(toNumber(r.qty) - toNumber(r.lockedQty)),
    },
    {
      title: '最近变动',
      dataIndex: 'lastMovementAt',
      width: 180,
      hideInSearch: true,
      render: (_, r) => <DateTimeColumn value={r.lastMovementAt} />,
    },
    {
      title: '更新时间',
      dataIndex: 'updatedAt',
      width: 180,
      hideInSearch: true,
      render: (_, r) => <DateTimeColumn value={r.updatedAt} />,
    },
  ];

  return (
    <ProTable<WmsInventory>
      headerTitle="库存余额"
      actionRef={actionRef}
      rowKey="id"
      columns={columns}
      search={{ labelWidth: 'auto' }}
      request={async (params) => {
        const warehouseId = (params.warehouseId as number | undefined) || undefined;
        const itemId = (params.itemId as number | undefined) || undefined;
        if (!warehouseId && !itemId) {
          // Backend requires at least one of warehouseId / itemId. Surface a
          // friendly hint instead of issuing a guaranteed-400 request.
          message.warning('请输入仓库 ID 或货品 ID 中的至少一个');
          return { data: [], total: 0, success: true };
        }
        const res = (await request.get('/wms/inventories', {
          params: {
            warehouseId,
            itemId,
            pageNum: params.current,
            pageSize: params.pageSize,
          },
        })) as unknown as PageResult<WmsInventory> | null;
        return { data: res?.records ?? [], total: res?.total ?? 0, success: true };
      }}
      pagination={{ defaultPageSize: 20, showSizeChanger: true }}
      toolBarRender={() => []}
      options={{
        reload: () => actionRef.current?.reload(),
        density: true,
        setting: true,
      }}
    />
  );
}
