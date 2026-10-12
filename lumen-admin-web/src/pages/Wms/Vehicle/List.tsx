/**
 * Lumen Admin — Dispatch Vehicle list (Iteration 4.1 PR3).
 *
 * 列出全部车辆 + 按 status/type 过滤；新建/编辑跳到 Form 路由.
 * 详情链接 -> /wms/vehicles/:id/edit （复用 Form 组件作为编辑入口）.
 *
 * 状态枚举来自 V20 VEHICLE 状态机:
 *   IDLE / DISPATCHED / LOADING / ENROUTE / RETURNING / OUT
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
  { value: 'IDLE',       text: '空闲',   status: 'Success' },
  { value: 'DISPATCHED', text: '已派车', status: 'Processing' },
  { value: 'LOADING',    text: '装载中', status: 'Processing' },
  { value: 'ENROUTE',    text: '在途',   status: 'Processing' },
  { value: 'RETURNING',  text: '回程',   status: 'Warning' },
  { value: 'OUT',        text: '停用',   status: 'Default' },
];

const TYPE_OPTIONS = [
  { value: 'SMALL',  label: '小型' },
  { value: 'MEDIUM', label: '中型' },
  { value: 'LARGE',  label: '大型' },
];

interface VehicleRow {
  id: number;
  plateNo: string;
  type?: string;
  maxLoadKg?: number;
  maxVolumeM3?: number;
  status?: string;
  driverId?: number;
  carrierId?: number;
  remark?: string;
  createdAt?: string;
  updatedAt?: string;
}

export default function DispatchVehicleList() {
  const actionRef = useRef<ActionType>();
  const nav = useNavigate();
  const { message: antMessage } = AntApp.useApp();
  const access = useAccess();

  const handleFreeze = async (id: number) => {
    await request.post(`/wms/vehicles/${id}/freeze`);
    antMessage.success('已冻结');
    actionRef.current?.reload();
  };

  const handleUnfreeze = async (id: number) => {
    await request.post(`/wms/vehicles/${id}/unfreeze`);
    antMessage.success('已解冻');
    actionRef.current?.reload();
  };

  const handleDelete = async (id: number) => {
    await request.delete(`/wms/vehicles/${id}`);
    antMessage.success('已删除');
    actionRef.current?.reload();
  };

  const columns: ProColumns<VehicleRow>[] = [
    { title: 'ID', dataIndex: 'id', width: 80 },
    { title: '车牌号', dataIndex: 'plateNo', width: 140 },
    {
      title: '车型',
      dataIndex: 'type',
      width: 100,
      valueType: 'select',
      fieldProps: { options: TYPE_OPTIONS },
    },
    { title: '载重(kg)', dataIndex: 'maxLoadKg', width: 120, hideInSearch: true },
    { title: '容积(m³)', dataIndex: 'maxVolumeM3', width: 120, hideInSearch: true },
    {
      title: '状态',
      dataIndex: 'status',
      width: 100,
      valueType: 'select',
      fieldProps: { options: STATUS_OPTIONS.map(({ value, text }) => ({ value, label: text })) },
      render: (_, r) => <StatusTag value={r.status ?? ''} />,
    },
    { title: '司机 ID', dataIndex: 'driverId', width: 100, hideInSearch: true },
    { title: '承运人 ID', dataIndex: 'carrierId', width: 100, hideInSearch: true },
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
      width: 280,
      render: (_, record) => [
        <Button
          key="edit"
          type="link"
          size="small"
          disabled={!access.canRead('dispatch:vehicle:update')}
          onClick={() => nav(`/dispatch/vehicles/${record.id}/edit`)}
        >
          编辑
        </Button>,
        record.status === 'OUT' ? (
          <Button
            key="unfreeze"
            type="link"
            size="small"
            disabled={!access.canRead('dispatch:vehicle:freeze')}
            onClick={() => handleUnfreeze(record.id)}
          >
            解冻
          </Button>
        ) : (
          <Popconfirm
            key="freeze"
            title="确认冻结该车辆?"
            okText="冻结"
            cancelText="取消"
            onConfirm={() => handleFreeze(record.id)}
          >
            <Button
              type="link"
              size="small"
              danger
              disabled={!access.canRead('dispatch:vehicle:freeze') || record.status === 'OUT'}
            >
              冻结
            </Button>
          </Popconfirm>
        ),
        <Popconfirm
          key="delete"
          title="确认删除该车辆?"
          okText="删除"
          cancelText="取消"
          onConfirm={() => handleDelete(record.id)}
        >
          <Button
            type="link"
            size="small"
            danger
            disabled={!access.canRead('dispatch:vehicle:update')}
          >
            删除
          </Button>
        </Popconfirm>,
      ],
    },
  ];

  return (
    <ProTable<VehicleRow>
      headerTitle="车辆管理"
      actionRef={actionRef}
      rowKey="id"
      columns={columns}
      search={{ labelWidth: 'auto' }}
      request={async (params) => {
        const res = (await request.get('/wms/vehicles', {
          params: {
            current: params.current,
            size: params.pageSize,
            status: params.status,
            type: params.type,
            plateNo: params.plateNo,
          },
        })) as unknown as PageResult<VehicleRow> | null;
        return { data: res?.records ?? [], total: res?.total ?? 0, success: true };
      }}
      pagination={{ defaultPageSize: 20, showSizeChanger: true }}
      toolBarRender={() => [
        <Button
          key="add"
          type="primary"
          disabled={!access.canRead('dispatch:vehicle:create')}
          onClick={() => nav('/dispatch/vehicles/new')}
        >
          新建车辆
        </Button>,
      ]}
    />
  );
}