/**
 * Lumen Admin — Users page.
 *
 * ProTable lists `/api/v1/users` (paged). ModalForm creates users via
 * `POST /users`; per-row edit/delete use the existing `PUT/DELETE /users/{id}`
 * endpoints (see UserController.java).
 *
 * Toolbar buttons and row actions are gated through `useAccess()` — the page
 * shows controls the current user's permission set actually allows.
 */

import type { ActionType, ProColumns } from '@ant-design/pro-components';
import {
  ModalForm,
  ProFormSelect,
  ProFormText,
  ProTable,
} from '@ant-design/pro-components';
import { App, Button, Popconfirm } from 'antd';
import { useRef, useState } from 'react';
import { useAccess } from '@/hooks/useAccess';
import { request } from '@/services/request';
import type { PageResult, SysUser } from '@/types/api';

const STATUS_ENUM = {
  1: { text: '启用', status: 'Success' as const },
  0: { text: '禁用', status: 'Default' as const },
};

export default function Users() {
  const actionRef = useRef<ActionType>();
  const { message } = App.useApp();
  const access = useAccess();
  const [createOpen, setCreateOpen] = useState(false);
  const [editing, setEditing] = useState<SysUser | null>(null);

  const reload = () => actionRef.current?.reload();

  const handleDelete = async (id: number) => {
    await request.delete(`/users/${id}`);
    message.success('已删除');
    reload();
  };

  const columns: ProColumns<SysUser>[] = [
    { title: 'ID', dataIndex: 'id', width: 80 },
    { title: '用户名', dataIndex: 'username', width: 160 },
    { title: '姓名', dataIndex: 'realName', width: 140 },
    { title: '邮箱', dataIndex: 'email', width: 200, hideInSearch: true },
    { title: '手机', dataIndex: 'phone', width: 140, hideInSearch: true },
    {
      title: '状态',
      dataIndex: 'status',
      width: 100,
      valueEnum: STATUS_ENUM,
      valueType: 'select',
    },
    {
      title: '最近登录',
      dataIndex: 'lastLoginAt',
      valueType: 'dateTime',
      width: 180,
      hideInSearch: true,
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
          disabled={!access.canRead('user:update')}
          onClick={() => setEditing(record)}
        >
          编辑
        </Button>,
        <Popconfirm
          key="delete"
          title="确认删除该用户?"
          okText="删除"
          cancelText="取消"
          onConfirm={() => handleDelete(record.id)}
        >
          <Button
            type="link"
            size="small"
            danger
            disabled={!access.canRead('user:delete')}
          >
            删除
          </Button>
        </Popconfirm>,
      ],
    },
  ];

  return (
    <>
      <ProTable<SysUser>
        headerTitle="用户管理"
        actionRef={actionRef}
        rowKey="id"
        columns={columns}
        toolBarRender={() => [
          <Button
            key="add"
            type="primary"
            disabled={!access.canRead('user:create')}
            onClick={() => setCreateOpen(true)}
          >
            新建
          </Button>,
        ]}
        request={async (params) => {
          // Response interceptor unwraps R<T> and returns the data field directly,
          // but axios's overload still types this as AxiosResponse<T>. Cast
          // through unknown so we can name the unwrapped shape.
          const res = (await request.get('/users', {
            params: {
              pageNum: params.current,
              pageSize: params.pageSize,
              keyword: params.keyword,
            },
          })) as unknown as PageResult<SysUser> | null;
          return { data: res?.records ?? [], total: res?.total ?? 0, success: true };
        }}
        pagination={{ defaultPageSize: 20, showSizeChanger: true }}
      />

      <ModalForm
        title="新建用户"
        open={createOpen}
        onOpenChange={setCreateOpen}
        modalProps={{ destroyOnClose: true }}
        onFinish={async (values) => {
          await request.post('/users', values);
          message.success('已创建');
          reload();
          return true;
        }}
      >
        <ProFormText
          name="username"
          label="用户名"
          rules={[{ required: true, message: '请输入用户名' }]}
          fieldProps={{ autoComplete: 'username' }}
        />
        <ProFormText
          name="realName"
          label="姓名"
          fieldProps={{ autoComplete: 'name' }}
        />
        <ProFormText
          name="email"
          label="邮箱"
          rules={[{ type: 'email', message: '邮箱格式不正确' }]}
        />
        <ProFormText
          name="phone"
          label="手机"
          fieldProps={{ autoComplete: 'tel' }}
        />
        <ProFormSelect
          name="status"
          label="状态"
          initialValue={1}
          valueEnum={{ 1: '启用', 0: '禁用' }}
        />
      </ModalForm>

      <ModalForm
        title={`编辑用户 — ${editing?.username ?? ''}`}
        open={editing !== null}
        onOpenChange={(open) => {
          if (!open) setEditing(null);
        }}
        modalProps={{ destroyOnClose: true }}
        initialValues={editing ?? undefined}
        onFinish={async (values) => {
          if (!editing) return false;
          await request.put(`/users/${editing.id}`, values);
          message.success('已更新');
          setEditing(null);
          reload();
          return true;
        }}
      >
        <ProFormText name="realName" label="姓名" />
        <ProFormText
          name="email"
          label="邮箱"
          rules={[{ type: 'email', message: '邮箱格式不正确' }]}
        />
        <ProFormText name="phone" label="手机" />
        <ProFormSelect
          name="status"
          label="状态"
          valueEnum={{ 1: '启用', 0: '禁用' }}
        />
      </ModalForm>
    </>
  );
}
