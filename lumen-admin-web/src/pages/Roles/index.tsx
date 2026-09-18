/**
 * Lumen Admin — Roles page.
 *
 * ProTable lists `/api/v1/roles` (paged). ModalForm creates roles; per-row
 * edit/delete use `POST/PUT/DELETE /roles` (see RoleController.java).
 *
 * `dataScope` enum maps to SysRole.dataScope (ALL / DEPT / DEPT_AND_SUB /
 * SELF / CUSTOM) — backend string column, frontend select with literal values.
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
import { useEffect, useRef, useState } from 'react';
import { getAccess, type AccessState } from '@/access';
import { request } from '@/services/request';
import type { DataScope, PageResult, SysRole } from '@/types/api';

const DATA_SCOPE_ENUM: Record<DataScope, { text: string }> = {
  ALL: { text: '全部' },
  DEPT: { text: '本部门' },
  DEPT_AND_SUB: { text: '本部门及下级' },
  SELF: { text: '仅本人' },
  CUSTOM: { text: '自定义' },
};

const STATUS_ENUM = {
  1: { text: '启用', status: 'Success' as const },
  0: { text: '禁用', status: 'Default' as const },
};

export default function Roles() {
  const actionRef = useRef<ActionType>();
  const { message } = App.useApp();
  const [access, setAccess] = useState<AccessState>({ canRead: () => false, perms: [] });
  const [createOpen, setCreateOpen] = useState(false);
  const [editing, setEditing] = useState<SysRole | null>(null);

  useEffect(() => {
    getAccess().then(setAccess);
  }, []);

  const reload = () => actionRef.current?.reload();

  const handleDelete = async (id: number) => {
    await request.delete(`/roles/${id}`);
    message.success('已删除');
    reload();
  };

  const columns: ProColumns<SysRole>[] = [
    { title: 'ID', dataIndex: 'id', width: 80 },
    { title: '编码', dataIndex: 'code', width: 160 },
    { title: '名称', dataIndex: 'name', width: 160 },
    { title: '描述', dataIndex: 'description', width: 200, ellipsis: true },
    {
      title: '数据权限',
      dataIndex: 'dataScope',
      width: 140,
      valueEnum: DATA_SCOPE_ENUM,
      valueType: 'select',
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 100,
      valueEnum: STATUS_ENUM,
      valueType: 'select',
    },
    {
      title: '创建时间',
      dataIndex: 'createdAt',
      valueType: 'dateTime',
      width: 180,
      hideInSearch: true,
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
          disabled={!access.canRead('role:update')}
          onClick={() => setEditing(record)}
        >
          编辑
        </Button>,
        <Popconfirm
          key="delete"
          title="确认删除该角色?"
          okText="删除"
          cancelText="取消"
          onConfirm={() => handleDelete(record.id)}
        >
          <Button
            type="link"
            size="small"
            danger
            disabled={!access.canRead('role:delete')}
          >
            删除
          </Button>
        </Popconfirm>,
      ],
    },
  ];

  return (
    <>
      <ProTable<SysRole>
        headerTitle="角色管理"
        actionRef={actionRef}
        rowKey="id"
        columns={columns}
        toolBarRender={() => [
          <Button
            key="add"
            type="primary"
            disabled={!access.canRead('role:create')}
            onClick={() => setCreateOpen(true)}
          >
            新建角色
          </Button>,
        ]}
        request={async (params) => {
          const res = (await request.get('/roles', {
            params: {
              pageNum: params.current,
              pageSize: params.pageSize,
              keyword: params.keyword,
            },
          })) as unknown as PageResult<SysRole> | null;
          return { data: res?.records ?? [], total: res?.total ?? 0, success: true };
        }}
        pagination={{ defaultPageSize: 20, showSizeChanger: true }}
      />

      <ModalForm
        title="新建角色"
        open={createOpen}
        onOpenChange={setCreateOpen}
        modalProps={{ destroyOnClose: true }}
        onFinish={async (values) => {
          await request.post('/roles', values);
          message.success('已创建');
          reload();
          return true;
        }}
      >
        <ProFormText
          name="code"
          label="编码"
          rules={[{ required: true, message: '请输入角色编码' }]}
        />
        <ProFormText name="name" label="名称" rules={[{ required: true, message: '请输入角色名称' }]} />
        <ProFormSelect
          name="dataScope"
          label="数据权限"
          initialValue="SELF"
          valueEnum={DATA_SCOPE_ENUM}
        />
        <ProFormTextArea name="description" label="描述" />
        <ProFormSelect
          name="status"
          label="状态"
          initialValue={1}
          valueEnum={{ 1: '启用', 0: '禁用' }}
        />
      </ModalForm>

      <ModalForm
        title={`编辑角色 — ${editing?.name ?? ''}`}
        open={editing !== null}
        onOpenChange={(open) => {
          if (!open) setEditing(null);
        }}
        modalProps={{ destroyOnClose: true }}
        initialValues={editing ?? undefined}
        onFinish={async (values) => {
          if (!editing) return false;
          await request.put(`/roles/${editing.id}`, values);
          message.success('已更新');
          setEditing(null);
          reload();
          return true;
        }}
      >
        <ProFormText name="name" label="名称" rules={[{ required: true, message: '请输入角色名称' }]} />
        <ProFormSelect name="dataScope" label="数据权限" valueEnum={DATA_SCOPE_ENUM} />
        <ProFormTextArea name="description" label="描述" />
        <ProFormSelect
          name="status"
          label="状态"
          valueEnum={{ 1: '启用', 0: '禁用' }}
        />
      </ModalForm>
    </>
  );
}
