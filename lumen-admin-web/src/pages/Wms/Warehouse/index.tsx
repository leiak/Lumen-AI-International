/**
 * Lumen Admin — WMS Warehouse list (PR1 vertical slice, Iteration 3.0a).
 *
 * ProTable lists `/api/v1/wms/warehouses` (paged). ModalForm creates /
 * edits warehouses; per-row delete calls `DELETE /wms/warehouses/{id}`
 * (see WmsWarehouseController.java).
 *
 * Page shape: `?pageNum&pageSize&keyword&status` — keyword is a server-side
 * LIKE across `code`/`name`; status is the enum string (e.g. "ACTIVE").
 *
 * Status column rendered via the shared <StatusTag/> which already maps the
 * known WmsLocationStatus codes (ACTIVE / FROZEN / DISABLED) — warehouses
 * share the same code vocabulary, so the existing mapping works without a
 * dedicated WmsWarehouseStatus enum in the frontend.
 */

import type { ActionType, ProColumns } from '@ant-design/pro-components';
import {
  ModalForm,
  ProFormSelect,
  ProFormText,
  ProFormTextArea,
  ProTable,
} from '@ant-design/pro-components';
import { App, Button, Popconfirm } from 'antd';
import { useRef, useState } from 'react';
import { DateTimeColumn } from '@/components/columns/DateTimeColumn';
import { StatusTag } from '@/components/columns/StatusTag';
import { useAccess } from '@/hooks/useAccess';
import { request } from '@/services/request';
import type { PageResult, WmsWarehouse } from '@/types/api';

/** Status filter + form select options — mirrors WmsLocationStatus codes. */
const STATUS_OPTIONS = [
  { value: 'ACTIVE', label: '启用' },
  { value: 'DISABLED', label: '禁用' },
];

/** Form shape — entity-level, status kept as a plain string. */
interface WarehouseFormValues {
  code: string;
  name: string;
  type?: string;
  status?: string;
  address?: string;
}

export default function WmsWarehousePage() {
  const actionRef = useRef<ActionType>();
  const { message } = App.useApp();
  const access = useAccess();
  const [createOpen, setCreateOpen] = useState(false);
  const [editing, setEditing] = useState<WmsWarehouse | null>(null);

  const reload = () => actionRef.current?.reload();

  const handleDelete = async (id: number) => {
    await request.delete(`/wms/warehouses/${id}`);
    message.success('已删除');
    reload();
  };

  const columns: ProColumns<WmsWarehouse>[] = [
    { title: 'ID', dataIndex: 'id', width: 80 },
    { title: '编码', dataIndex: 'code', width: 160 },
    { title: '名称', dataIndex: 'name', width: 200 },
    { title: '类型', dataIndex: 'type', width: 120, hideInSearch: true },
    {
      title: '状态',
      dataIndex: 'status',
      width: 100,
      valueType: 'select',
      valueEnum: {
        ACTIVE: { text: '启用', status: 'Success' },
        DISABLED: { text: '禁用', status: 'Default' },
      },
      render: (_, record) => <StatusTag value={record.status ?? ''} />,
    },
    { title: '地址', dataIndex: 'address', ellipsis: true, hideInSearch: true },
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
      width: 200,
      render: (_, record) => [
        <Button
          key="edit"
          type="link"
          size="small"
          disabled={!access.canRead('wms:warehouse:update')}
          onClick={() => setEditing(record)}
        >
          编辑
        </Button>,
        <Popconfirm
          key="delete"
          title="确认删除该仓库?"
          okText="删除"
          cancelText="取消"
          onConfirm={() => handleDelete(record.id)}
        >
          <Button
            type="link"
            size="small"
            danger
            disabled={!access.canRead('wms:warehouse:delete')}
          >
            删除
          </Button>
        </Popconfirm>,
      ],
    },
  ];

  return (
    <>
      <ProTable<WmsWarehouse>
        headerTitle="仓库管理"
        actionRef={actionRef}
        rowKey="id"
        columns={columns}
        toolBarRender={() => [
          <Button
            key="add"
            type="primary"
            disabled={!access.canRead('wms:warehouse:create')}
            onClick={() => setCreateOpen(true)}
          >
            新建仓库
          </Button>,
        ]}
        request={async (params) => {
          const res = (await request.get('/wms/warehouses', {
            params: {
              pageNum: params.current,
              pageSize: params.pageSize,
              keyword: params.keyword,
              status: params.status,
            },
          })) as unknown as PageResult<WmsWarehouse> | null;
          return { data: res?.records ?? [], total: res?.total ?? 0, success: true };
        }}
        pagination={{ defaultPageSize: 20, showSizeChanger: true }}
      />

      <ModalForm<WarehouseFormValues>
        title="新建仓库"
        open={createOpen}
        onOpenChange={setCreateOpen}
        modalProps={{ destroyOnClose: true }}
        onFinish={async (values) => {
          await request.post('/wms/warehouses', values);
          message.success('已创建');
          reload();
          return true;
        }}
      >
        <ProFormText
          name="code"
          label="编码"
          rules={[{ required: true, message: '请输入仓库编码' }]}
        />
        <ProFormText
          name="name"
          label="名称"
          rules={[{ required: true, message: '请输入仓库名称' }]}
        />
        <ProFormText name="type" label="类型" placeholder="例如: NORMAL / BONDED" />
        <ProFormSelect
          name="status"
          label="状态"
          initialValue="ACTIVE"
          options={STATUS_OPTIONS}
        />
        <ProFormTextArea name="address" label="地址" />
      </ModalForm>

      <ModalForm<WarehouseFormValues>
        title={`编辑仓库 — ${editing?.name ?? ''}`}
        open={editing !== null}
        onOpenChange={(open) => {
          if (!open) setEditing(null);
        }}
        modalProps={{ destroyOnClose: true }}
        initialValues={editing ?? undefined}
        onFinish={async (values) => {
          if (!editing) return false;
          await request.put(`/wms/warehouses/${editing.id}`, values);
          message.success('已更新');
          setEditing(null);
          reload();
          return true;
        }}
      >
        <ProFormText name="code" label="编码" />
        <ProFormText name="name" label="名称" />
        <ProFormText name="type" label="类型" />
        <ProFormSelect name="status" label="状态" options={STATUS_OPTIONS} />
        <ProFormTextArea name="address" label="地址" />
      </ModalForm>
    </>
  );
}