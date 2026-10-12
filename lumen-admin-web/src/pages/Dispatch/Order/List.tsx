/**
 * Lumen Admin — DeliveryOrder list (Iteration 4.1 PR4).
 *
 * 配送单列表：状态机按钮触发"派车/装车/出发/送达/签收/取消/异常"。
 * 状态映射见 StatusTag 组件的 DO_/STOP_/EX_ 行。
 */

import type { ActionType, ProColumns } from '@ant-design/pro-components';
import { ProTable } from '@ant-design/pro-components';
import { App as AntApp, Button } from 'antd';
import { useRef } from 'react';
import { useNavigate } from 'react-router-dom';

import { DateTimeColumn } from '@/components/columns/DateTimeColumn';
import { StatusTag } from '@/components/columns/StatusTag';
import { useAccess } from '@/hooks/useAccess';
import { deliveryOrderService } from '@/services/dispatch/deliveryOrder';

const STATUS_OPTIONS = [
  { value: 'PENDING_DISPATCH', label: '待派车' },
  { value: 'DISPATCHED',       label: '已派车' },
  { value: 'LOADED',           label: '已装车' },
  { value: 'ENROUTE',          label: '在途' },
  { value: 'ARRIVED',          label: '已到达' },
  { value: 'SIGNED',           label: '已签收' },
  { value: 'CANCELLED',        label: '已取消' },
  { value: 'EXCEPTION',        label: '异常' },
];

interface OrderRow {
  id: number;
  no: string;
  sourceNo: string;
  status: string;
  warehouseId: number;
  vehicleId?: number;
  driverId?: number;
  totalWeightKg?: number;
  totalVolumeM3?: number;
  stopCount?: number;
  createdAt?: string;
  updatedAt?: string;
}

export default function DeliveryOrderList() {
  const actionRef = useRef<ActionType>();
  const nav = useNavigate();
  const { message: antMessage } = AntApp.useApp();
  const access = useAccess();

  const handleAutoAssign = async (id: number) => {
    await deliveryOrderService.autoAssign(id);
    antMessage.success('自动派车成功');
    actionRef.current?.reload();
  };

  const columns: ProColumns<OrderRow>[] = [
    { title: '配送单号', dataIndex: 'no', width: 180 },
    { title: '源单号', dataIndex: 'sourceNo', width: 180 },
    { title: '仓库 ID', dataIndex: 'warehouseId', width: 100, hideInSearch: true },
    { title: '车辆 ID', dataIndex: 'vehicleId', width: 100, hideInSearch: true },
    { title: '司机 ID', dataIndex: 'driverId', width: 100, hideInSearch: true },
    {
      title: '重量 (kg)', dataIndex: 'totalWeightKg', width: 100, hideInSearch: true,
      render: (_, r) => r.totalWeightKg ?? '—',
    },
    {
      title: '体积 (m³)', dataIndex: 'totalVolumeM3', width: 100, hideInSearch: true,
      render: (_, r) => r.totalVolumeM3 ?? '—',
    },
    { title: '站点数', dataIndex: 'stopCount', width: 90, hideInSearch: true },
    {
      title: '状态',
      dataIndex: 'status',
      width: 110,
      valueType: 'select',
      fieldProps: { options: STATUS_OPTIONS },
      render: (_, r) => <StatusTag value={r.status} />,
    },
    {
      title: '更新时间',
      dataIndex: 'updatedAt',
      width: 180,
      hideInSearch: true,
      render: (_, r) => <DateTimeColumn value={r.updatedAt} />,
    },
    {
      title: '操作',
      valueType: 'option',
      width: 280,
      render: (_, record) => [
        <Button
          key="detail"
          type="link"
          size="small"
          disabled={!access.canRead('dispatch:order:list')}
          onClick={() => nav(`/dispatch/orders/${record.id}`)}
        >
          详情
        </Button>,
        record.status === 'PENDING_DISPATCH' && (
          <Button
            key="auto-assign"
            type="link"
            size="small"
            disabled={!access.canRead('dispatch:order:assign')}
            onClick={() => handleAutoAssign(record.id)}
          >
            自动派车
          </Button>
        ),
      ],
    },
  ];

  return (
    <ProTable<OrderRow>
      headerTitle="配送单管理"
      actionRef={actionRef}
      rowKey="id"
      columns={columns}
      search={{ labelWidth: 'auto' }}
      request={async (params) => {
        const res = await deliveryOrderService.page({
          pageNum: params.current,
          pageSize: params.pageSize,
          status: params.status,
          keyword: params.no,
        });
        return { data: res.records, total: res.total, success: true };
      }}
      pagination={{ defaultPageSize: 20, showSizeChanger: true }}
      toolBarRender={() => [
        <Button
          key="add"
          type="primary"
          disabled={!access.canRead('dispatch:order:create')}
          onClick={() => nav('/dispatch/orders/new')}
        >
          新建配送单
        </Button>,
      ]}
    />
  );
}
