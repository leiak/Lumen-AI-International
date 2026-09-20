/**
 * Lumen Admin — Users page.
 *
 * ProTable lists `/api/v1/users` (paged). ModalForm creates users via
 * `POST /users`; per-row edit/delete use the existing `PUT/DELETE /users/{id}`
 * endpoints (see UserController.java).
 *
 * The "分配角色" Modal fetches the role catalog from `/roles` (paged with a
 * large pageSize so the checkbox list is complete) and the user's currently
 * assigned role IDs from `/users/{id}/roles`, then PUTs the full role ID list
 * back to replace the assignment set (mirrors how the Roles page handles
 * 分配权限 against `/roles/{id}/permissions`).
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
import { App, Button, Checkbox, Modal, Popconfirm, Space, Spin } from 'antd';
import { useRef, useState } from 'react';
import { useAccess } from '@/hooks/useAccess';
import { request } from '@/services/request';
import type { PageResult, SysRole, SysUser } from '@/types/api';

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

  // 分配角色 Modal — keeps the user being edited + role checkbox state
  // isolated so reopening for a different user doesn't leak prior selections.
  const [assignOpen, setAssignOpen] = useState(false);
  const [assigningUser, setAssigningUser] = useState<SysUser | null>(null);
  const [allRoles, setAllRoles] = useState<SysRole[]>([]);
  const [selectedRoleIds, setSelectedRoleIds] = useState<number[]>([]);
  const [assignLoading, setAssignLoading] = useState(false);
  const [assignSubmitting, setAssignSubmitting] = useState(false);

  const reload = () => actionRef.current?.reload();

  const handleDelete = async (id: number) => {
    await request.delete(`/users/${id}`);
    message.success('已删除');
    reload();
  };

  const openAssignModal = async (user: SysUser) => {
    setAssigningUser(user);
    setAssignOpen(true);
    setAssignLoading(true);
    setAllRoles([]);
    setSelectedRoleIds([]);
    try {
      // Fetch the full role catalog (large pageSize so the checkbox list is
      // complete) and the user's currently assigned role IDs in parallel.
      const [rolesRes, assignedRes] = await Promise.all([
        request.get('/roles', { params: { pageNum: 1, pageSize: 1000 } }) as unknown as Promise<PageResult<SysRole> | null>,
        request.get(`/users/${user.id}/roles`) as unknown as Promise<number[] | null>,
      ]);
      setAllRoles(rolesRes?.records ?? []);
      setSelectedRoleIds(assignedRes ?? []);
    } catch {
      // axios interceptor surfaces the error toast; close the modal so the
      // operator isn't left staring at an empty checkbox list.
      setAssignOpen(false);
      setAssigningUser(null);
    } finally {
      setAssignLoading(false);
    }
  };

  const closeAssignModal = () => {
    setAssignOpen(false);
    setAssigningUser(null);
    setAllRoles([]);
    setSelectedRoleIds([]);
  };

  const saveAssign = async () => {
    if (!assigningUser) return;
    setAssignSubmitting(true);
    try {
      await request.put(`/users/${assigningUser.id}/roles`, selectedRoleIds);
      message.success('角色分配成功');
      closeAssignModal();
      reload();
    } catch {
      // axios interceptor already shows the error notification.
    } finally {
      setAssignSubmitting(false);
    }
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
      width: 240,
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
        <Button
          key="assign"
          type="link"
          size="small"
          disabled={!access.canRead('user:assign-role')}
          onClick={() => openAssignModal(record)}
        >
          分配角色
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

      <Modal
        title={assigningUser ? `分配角色 — ${assigningUser.username}` : ''}
        open={assignOpen}
        onCancel={closeAssignModal}
        onOk={saveAssign}
        confirmLoading={assignSubmitting}
        okText="保存"
        cancelText="取消"
        width={520}
        destroyOnClose
        maskClosable={false}
      >
        <Spin spinning={assignLoading}>
          <Checkbox.Group
            value={selectedRoleIds}
            onChange={(v) => setSelectedRoleIds(v as number[])}
            style={{ width: '100%' }}
          >
            <Space direction="vertical" style={{ width: '100%' }}>
              {allRoles.map((role) => (
                <Checkbox key={role.id} value={role.id} disabled={role.status !== 1}>
                  <Space>
                    <span>{role.name}</span>
                    <span style={{ color: '#999', fontSize: 12 }}>{role.code}</span>
                  </Space>
                </Checkbox>
              ))}
            </Space>
          </Checkbox.Group>
        </Spin>
      </Modal>
    </>
  );
}
