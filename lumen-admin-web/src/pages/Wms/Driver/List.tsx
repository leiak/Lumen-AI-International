/**
 * Lumen Admin — Dispatch Driver list (Iteration 4.1 PR3).
 *
 * 司机主数据列表；状态机极简 ACTIVE/SUSPENDED，支持 suspend / resume 操作。
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
  { value: 'ACTIVE',    label: '在职' },
  { value: 'SUSPENDED', label: '停用' },
];

interface DriverRow {
  id: number;
  name: string;
  phone?: string;
  licenseNo?: string;
  status?: string;
  remark?: string;
  createdAt?: string;
  updatedAt?: string;
}

export default function DispatchDriverList() {
  const actionRef = useRef<ActionType>();
  const nav = useNavigate();
  const { message: antMessage } = AntApp.useApp();
  const access = useAccess();

  const handleSuspend = async (id: number) => {
    await request.post(`/wms/drivers/${id}/suspend`);
    antMessage.success('已停用');
    actionRef.current?.reload();
  };

  const handleResume = async (id: number) => {
    await request.post(`/wms/drivers/${id}/resume`);
    antMessage.success('已恢复');
    actionRef.current?.reload();
  };

  const handleDelete = async (id: number) => {
    await request.delete(`/wms/drivers/${id}`);
    antMessage.success('已删除');
    actionRef.current?.reload();
  };

  const columns: ProColumns<DriverRow>[] = [
    { title: 'ID', dataIndex: 'id', width: 80 },
    { title: '姓名', dataIndex: 'name', width: 140 },
    { title: '电话', dataIndex: 'phone', width: 140 },
    { title: '驾驶证号', dataIndex: 'licenseNo', width: 160, hideInSearch: true },
    {
      title: '状态',
      dataIndex: 'status',
      width: 100,
      valueType: 'select',
      fieldProps: { options: STATUS_OPTIONS },
      render: (_, r) => <StatusTag value={r.status ?? ''} />,
    },
    { title: '备注', dataIndex: 'remark', ellipsis: true, hideInSearch: true },
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
      width: 240,
      render: (_, record) => [
        <Button
          key="edit"
          type="link"
          size="small"
          disabled={!access.canRead('dispatch:driver:update')}
          onClick={() => nav(`/dispatch/drivers/${record.id}/edit`)}
        >
          编辑
        </Button>,
        record.status === 'SUSPENDED' ? (
          <Button
            key="resume"
            type="link"
            size="small"
            disabled={!access.canRead('dispatch:driver:suspend')}
            onClick={() => handleResume(record.id)}
          >
            恢复
          </Button>
        ) : (
          <Popconfirm
            key="suspend"
            title="确认停用该司机?"
            okText="停用"
            cancelText="取消"
            onConfirm={() => handleSuspend(record.id)}
          >
            <Button
              type="link"
              size="small"
              danger
              disabled={!access.canRead('dispatch:driver:suspend')}
            >
              停用
            </Button>
          </Popconfirm>
        ),
        <Popconfirm
          key="delete"
          title="确认删除该司机?"
          okText="删除"
          cancelText="取消"
          onConfirm={() => handleDelete(record.id)}
        >
          <Button
            type="link"
            size="small"
            danger
            disabled={!access.canRead('dispatch:driver:update')}
          >
            删除
          </Button>
        </Popconfirm>,
      ],
    },
  ];

  return (
    <ProTable<DriverRow>
      headerTitle="司机管理"
      actionRef={actionRef}
      rowKey="id"
      columns={columns}
      search={{ labelWidth: 'auto' }}
      request={async (params) => {
        const res = (await request.get('/wms/drivers', {
          params: {
            current: params.current,
            size: params.pageSize,
            status: params.status,
            name: params.name,
          },
        })) as unknown as PageResult<DriverRow> | null;
        return { data: res?.records ?? [], total: res?.total ?? 0, success: true };
      }}
      pagination={{ defaultPageSize: 20, showSizeChanger: true }}
      toolBarRender={() => [
        <Button
          key="add"
          type="primary"
          disabled={!access.canRead('dispatch:driver:create')}
          onClick={() => nav('/dispatch/drivers/new')}
        >
          新建司机
        </Button>,
      ]}
    />
  );
}