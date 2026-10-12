/**
 * Lumen Admin — DeliveryException list (Iteration 4.1 PR4).
 *
 * 异常工单列表。5 个状态机迁移：investigate/resolve/close/reopen/cancel
 * 详情页 PR4 实现。
 */

import type { ActionType, ProColumns } from '@ant-design/pro-components';
import { ProTable } from '@ant-design/pro-components';
import { Button } from 'antd';
import { useRef } from 'react';
import { useNavigate } from 'react-router-dom';

import { DateTimeColumn } from '@/components/columns/DateTimeColumn';
import { StatusTag } from '@/components/columns/StatusTag';
import { useAccess } from '@/hooks/useAccess';
import { deliveryExceptionService } from '@/services/dispatch/exception';

const STATUS_OPTIONS = [
  { value: 'OPEN',          label: '待处理' },
  { value: 'INVESTIGATING', label: '调查中' },
  { value: 'RESOLVED',      label: '已解决' },
  { value: 'CLOSED',        label: '已关闭' },
  { value: 'CANCELLED',     label: '已撤销' },
];

const TYPE_OPTIONS = [
  { value: 'REJECTED',  label: '拒收' },
  { value: 'OVERTIME',  label: '超时' },
  { value: 'DAMAGE',    label: '货损' },
  { value: 'ADDR_ERR',  label: '地址错误' },
];

interface ExRow {
  id: number;
  orderId: number;
  type: string;
  status: string;
  description?: string;
  handler?: string;
  resolvedAt?: string;
  createdAt?: string;
  updatedAt?: string;
}

export default function DeliveryExceptionList() {
  const actionRef = useRef<ActionType>();
  const nav = useNavigate();
  const access = useAccess();

  const columns: ProColumns<ExRow>[] = [
    { title: '工单 ID', dataIndex: 'id', width: 90 },
    { title: '关联订单', dataIndex: 'orderId', width: 110 },
    {
      title: '类型',
      dataIndex: 'type',
      width: 110,
      valueType: 'select',
      fieldProps: { options: TYPE_OPTIONS },
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 110,
      valueType: 'select',
      fieldProps: { options: STATUS_OPTIONS },
      render: (_, r) => <StatusTag value={r.status} />,
    },
    { title: '处理人', dataIndex: 'handler', width: 120, hideInSearch: true },
    { title: '描述', dataIndex: 'description', ellipsis: true, hideInSearch: true },
    {
      title: '解决时间',
      dataIndex: 'resolvedAt',
      width: 170,
      hideInSearch: true,
      render: (_, r) => <DateTimeColumn value={r.resolvedAt} />,
    },
    {
      title: '创建时间',
      dataIndex: 'createdAt',
      width: 180,
      hideInSearch: true,
      render: (_, r) => <DateTimeColumn value={r.createdAt} />,
    },
    {
      title: '操作',
      valueType: 'option',
      width: 100,
      render: (_, record) => [
        <Button
          key="detail"
          type="link"
          size="small"
          disabled={!access.canRead('dispatch:exception:list')}
          onClick={() => nav(`/dispatch/exceptions/${record.id}`)}
        >
          详情
        </Button>,
      ],
    },
  ];

  return (
    <ProTable<ExRow>
      headerTitle="配送异常工单"
      actionRef={actionRef}
      rowKey="id"
      columns={columns}
      search={{ labelWidth: 'auto' }}
      request={async (params) => {
        const res = await deliveryExceptionService.page({
          pageNum: params.current,
          pageSize: params.pageSize,
          status: params.status,
        });
        return { data: res.records, total: res.total, success: true };
      }}
      pagination={{ defaultPageSize: 20, showSizeChanger: true }}
    />
  );
}
