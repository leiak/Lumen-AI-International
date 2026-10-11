/**
 * Lumen Admin — WMS Outbound Notes list (PR4-T4.3, Iteration 3.0a).
 *
 * 列出全部出库单 + 按状态过滤;行点击进入详情页.
 * 状态机进入点 (新建/编辑) 放在详情页 PR5 阶段统一接入.
 */

import type { ProColumns } from '@ant-design/pro-components';
import { ProTable } from '@ant-design/pro-components';
import { Button, Tag } from 'antd';
import { useRef } from 'react';
import { useNavigate } from 'react-router-dom';

import { DateTimeColumn } from '@/components/columns/DateTimeColumn';
import { useAccess } from '@/hooks/useAccess';
import { request } from '@/services/request';
import type { PageResult } from '@/types/api';

const STATUS_LABEL: Record<string, { text: string; color: string }> = {
  DRAFT:            { text: '草稿',   color: 'default' },
  PENDING_APPROVAL: { text: '待审批', color: 'orange' },
  PICKING:          { text: '拣货中', color: 'blue' },
  REVIEW:           { text: '复核中', color: 'cyan' },
  SHIPPED:          { text: '已发货', color: 'gold' },
  DONE:             { text: '已完成', color: 'green' },
  CANCELLED:        { text: '已取消', color: 'red' },
};

interface OutboundNoteRow {
  id: number;
  no: string;
  customerId: number;
  warehouseId: number;
  status: string;
  carrier?: string;
  trackingNo?: string;
  requestedAt?: string;
  shippedAt?: string;
  createdAt?: string;
  updatedAt?: string;
}

function statusTag(status: string) {
  const m = STATUS_LABEL[status] ?? { text: status, color: 'default' };
  return <Tag color={m.color}>{m.text}</Tag>;
}

export default function OutboundListPage() {
  const actionRef = useRef();
  const nav = useNavigate();
  const access = useAccess();

  const columns: ProColumns<OutboundNoteRow>[] = [
    { title: '单号', dataIndex: 'no', width: 200 },
    { title: '客户 ID', dataIndex: 'customerId', width: 100, hideInSearch: true },
    { title: '仓库 ID', dataIndex: 'warehouseId', width: 100, hideInSearch: true },
    { title: '承运商', dataIndex: 'carrier', width: 120, hideInSearch: true,
      render: (_, r) => r.carrier ?? '—' },
    { title: '运单号', dataIndex: 'trackingNo', width: 160, hideInSearch: true,
      render: (_, r) => r.trackingNo ?? '—' },
    {
      title: '状态',
      dataIndex: 'status',
      width: 100,
      valueType: 'select',
      fieldProps: {
        options: Object.entries(STATUS_LABEL).map(([value, { text }]) => ({ value, label: text })),
      },
      render: (_, r) => statusTag(r.status),
    },
    {
      title: '请求时间',
      dataIndex: 'requestedAt',
      width: 170,
      hideInSearch: true,
      render: (_, r) => <DateTimeColumn value={r.requestedAt} />,
    },
    {
      title: '发货时间',
      dataIndex: 'shippedAt',
      width: 170,
      hideInSearch: true,
      render: (_, r) => <DateTimeColumn value={r.shippedAt} />,
    },
    {
      title: '创建时间',
      dataIndex: 'createdAt',
      width: 170,
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
          disabled={!access.canRead('wms:outbound:list')}
          onClick={() => nav(`/wms/outbound-notes/${record.id}`)}
        >
          详情
        </Button>,
      ],
    },
  ];

  return (
    <ProTable<OutboundNoteRow>
      headerTitle="出库单列表"
      actionRef={actionRef}
      rowKey="id"
      columns={columns}
      search={{ labelWidth: 'auto' }}
      request={async (params) => {
        const res = (await request.get('/wms/outbound-notes', {
          params: {
            pageNum: params.current,
            pageSize: params.pageSize,
            keyword: params.no,
            status: params.status,
          },
        })) as unknown as PageResult<OutboundNoteRow> | null;
        return { data: res?.records ?? [], total: res?.total ?? 0, success: true };
      }}
      pagination={{ defaultPageSize: 20, showSizeChanger: true }}
      toolBarRender={() => [
        <Button
          key="add"
          type="primary"
          disabled={!access.canRead('wms:outbound:create')}
          onClick={() => nav('/wms/outbound-notes/new')}
        >
          新建出库单
        </Button>,
      ]}
    />
  );
}
