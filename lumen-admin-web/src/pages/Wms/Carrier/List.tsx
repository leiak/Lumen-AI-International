/**
 * Lumen Admin — Dispatch Carrier list (Iteration 4.1 PR3).
 *
 * 承运人主数据列表（自有车队 / 外协）；状态机 ACTIVE/SUSPENDED 二选一，
 * 跟司机同模式，无独立状态机引擎（service 直接 setStatus）。
 */

import type { ActionType, ProColumns } from '@ant-design/pro-components';
import { ProTable } from '@ant-design/pro-components';
import { Button, Popconfirm, App as AntApp } from 'antd';
import { useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import { DateTimeColumn } from '@/components/columns/DateTimeColumn';
import { StatusTag } from '@/components/columns/StatusTag';
import { useAccess } from '@/hooks/useAccess';
import { request } from '@/services/request';
import type { PageResult } from '@/types/api';

const STATUS_OPTIONS = [
  { value: 'ACTIVE',    label: '启用' },
  { value: 'SUSPENDED', label: '停用' },
];

interface CarrierRow {
  id: number;
  code: string;
  name: string;
  contact?: string;
  status?: string;
  createdAt?: string;
  updatedAt?: string;
}

export default function DispatchCarrierList() {
  const actionRef = useRef<ActionType>();
  const nav = useNavigate();
  const { message: antMessage } = AntApp.useApp();
  const access = useAccess();

  const handleDelete = async (id: number) => {
    await request.delete(`/wms/carriers/${id}`);
    antMessage.success('已删除');
    actionRef.current?.reload();
  };

  const columns: ProColumns<CarrierRow>[] = [
    { title: 'ID', dataIndex: 'id', width: 80 },
    { title: '编码', dataIndex: 'code', width: 140 },
    { title: '名称', dataIndex: 'name', width: 200 },
    { title: '联系方式', dataIndex: 'contact', width: 160, hideInSearch: true },
    {
      title: '状态',
      dataIndex: 'status',
      width: 100,
      valueType: 'select',
      fieldProps: { options: STATUS_OPTIONS },
      render: (_, r) => <StatusTag value={r.status ?? ''} />,
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
      width: 180,
      render: (_, record) => [
        <Button
          key="edit"
          type="link"
          size="small"
          disabled={!access.canRead('dispatch:carrier:update')}
          onClick={() => nav(`/dispatch/carriers/${record.id}/edit`)}
        >
          编辑
        </Button>,
        <Popconfirm
          key="delete"
          title="确认删除该承运人?"
          okText="删除"
          cancelText="取消"
          onConfirm={() => handleDelete(record.id)}
        >
          <Button
            type="link"
            size="small"
            danger
            disabled={!access.canRead('dispatch:carrier:update')}
          >
            删除
          </Button>
        </Popconfirm>,
      ],
    },
  ];

  return (
    <ProTable<CarrierRow>
      headerTitle="承运人管理"
      actionRef={actionRef}
      rowKey="id"
      columns={columns}
      search={{ labelWidth: 'auto' }}
      request={async (params) => {
        const res = (await request.get('/wms/carriers', {
          params: {
            current: params.current,
            size: params.pageSize,
            status: params.status,
            code: params.code,
            name: params.name,
          },
        })) as unknown as PageResult<CarrierRow> | null;
        return { data: res?.records ?? [], total: res?.total ?? 0, success: true };
      }}
      pagination={{ defaultPageSize: 20, showSizeChanger: true }}
      toolBarRender={() => [
        <Button
          key="add"
          type="primary"
          disabled={!access.canRead('dispatch:carrier:create')}
          onClick={() => nav('/dispatch/carriers/new')}
        >
          新建承运人
        </Button>,
      ]}
    />
  );
}