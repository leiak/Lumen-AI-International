/**
 * Lumen Admin — WMS Inventory Adjust Notes list (PR5-T5.2, Iteration 3.0a).
 *
 * 列出全部库存调整单 + 按状态过滤;行点击进入详情页.
 * 新建表单放到后续 PR5 共享组件 PR — 本 PR 只暴露入口.
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
  DRAFT:            { text: '草稿',     color: 'default' },
  PENDING_APPROVAL: { text: '待审批',   color: 'orange' },
  APPROVED:         { text: '已审批',   color: 'blue' },
  DONE:             { text: '已完成',   color: 'green' },
  REJECTED:         { text: '已驳回',   color: 'volcano' },
  CANCELLED:        { text: '已取消',   color: 'red' },
};

interface AdjustNoteRow {
  id: number;
  no: string;
  warehouseId: number;
  status: string;
  reason?: string;
  rejectReason?: string;
  approvedBy?: number;
  approvedAt?: string;
  remark?: string;
  createdAt?: string;
  updatedAt?: string;
}

function statusTag(status: string) {
  const m = STATUS_LABEL[status] ?? { text: status, color: 'default' };
  return <Tag color={m.color}>{m.text}</Tag>;
}

export default function AdjustListPage() {
  const actionRef = useRef();
  const nav = useNavigate();
  const access = useAccess();

  const columns: ProColumns<AdjustNoteRow>[] = [
    { title: '单号', dataIndex: 'no', width: 200 },
    { title: '仓库 ID', dataIndex: 'warehouseId', width: 100, hideInSearch: true },
    {
      title: '原因',
      dataIndex: 'reason',
      width: 220,
      hideInSearch: true,
      ellipsis: true,
      render: (_, r) => r.reason ?? '—',
    },
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
      title: '审批时间',
      dataIndex: 'approvedAt',
      width: 170,
      hideInSearch: true,
      render: (_, r) => <DateTimeColumn value={r.approvedAt} />,
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
          disabled={!access.canRead('wms:adjust:list')}
          onClick={() => nav(`/wms/inventory-adjusts/${record.id}`)}
        >
          详情
        </Button>,
      ],
    },
  ];

  return (
    <ProTable<AdjustNoteRow>
      headerTitle="调整单列表"
      actionRef={actionRef}
      rowKey="id"
      columns={columns}
      search={{ labelWidth: 'auto' }}
      request={async (params) => {
        const res = (await request.get('/wms/inventory-adjusts', {
          params: {
            pageNum: params.current,
            pageSize: params.pageSize,
            keyword: params.no,
            status: params.status,
          },
        })) as unknown as PageResult<AdjustNoteRow> | null;
        return { data: res?.records ?? [], total: res?.total ?? 0, success: true };
      }}
      pagination={{ defaultPageSize: 20, showSizeChanger: true }}
      toolBarRender={() => [
        <Button
          key="add"
          type="primary"
          disabled={!access.canRead('wms:adjust:create')}
          onClick={() => nav('/wms/inventory-adjusts/new')}
        >
          新建调整单
        </Button>,
      ]}
    />
  );
}
