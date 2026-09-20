/**
 * Lumen Admin — Roles page.
 *
 * ProTable lists `/api/v1/roles` (paged). ModalForm creates roles; per-row
 * edit/delete use `POST/PUT/DELETE /roles` (see RoleController.java).
 *
 * `dataScope` enum maps to SysRole.dataScope (ALL / DEPT / DEPT_AND_SUB /
 * SELF / CUSTOM) — backend string column, frontend select with literal values.
 *
 * The "分配权限" Modal reuses `GET /api/v1/permissions` (flat SysPermission
 * list) — same endpoint the Permissions page calls. The flat list is grouped
 * into an antd Tree by `parentId` (preferred — preserves the RBAC menu
 * hierarchy) with a type-based fallback when the data has no parent links.
 * Saving PUTs `List<Long>` of leaf permission IDs to
 * `PUT /api/v1/roles/{id}/permissions`.
 */

import type { ActionType, ProColumns } from '@ant-design/pro-components';
import {
  ModalForm,
  ProFormSelect,
  ProFormText,
  ProFormTextArea,
  ProTable,
} from '@ant-design/pro-components';
import { App, Button, Modal, Popconfirm, Spin, Tree } from 'antd';
import type { DataNode } from 'antd/es/tree';
import { useMemo, useRef, useState } from 'react';
import { useAccess } from '@/hooks/useAccess';
import { request } from '@/services/request';
import type {
  DataScope,
  PageResult,
  PermissionType,
  SysPermission,
  SysRole,
} from '@/types/api';

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

/** Human label for each permission type — used as the fallback group heading. */
const TYPE_LABEL: Record<PermissionType, string> = {
  MENU: '菜单',
  BUTTON: '按钮',
  API: '接口',
};

export default function Roles() {
  const actionRef = useRef<ActionType>();
  const { message } = App.useApp();
  const access = useAccess();
  const [createOpen, setCreateOpen] = useState(false);
  const [editing, setEditing] = useState<SysRole | null>(null);

  // 分配权限 Modal — keeps the role being edited + tree state isolated so
  // opening it for a different role doesn't leak prior selections.
  const [assignOpen, setAssignOpen] = useState(false);
  const [assigningRole, setAssigningRole] = useState<SysRole | null>(null);
  const [assignTree, setAssignTree] = useState<DataNode[]>([]);
  const [assignChecked, setAssignChecked] = useState<number[]>([]);
  const [assignLoading, setAssignLoading] = useState(false);
  const [assignSubmitting, setAssignSubmitting] = useState(false);

  const reload = () => actionRef.current?.reload();

  const handleDelete = async (id: number) => {
    await request.delete(`/roles/${id}`);
    message.success('已删除');
    reload();
  };

  /**
   * Group the flat permission list into an antd Tree shape.
   *
   * - Primary strategy: parentId-based hierarchy. Roots are permissions with
   *   no parentId; leaves are their direct children. Module keys are strings
   *   (`module-<id>`) so the leaf-filter step below can tell them apart from
   *   numeric permission IDs.
   * - Fallback: when no root exists (all rows are orphans or peer-level),
   *   group by `type` so the operator still gets a navigable tree instead of
   *   one giant flat list.
   * - Tertiary: if everything fails, render the rows as flat roots.
   */
  const buildPermissionTree = (perms: SysPermission[]): DataNode[] => {
    if (perms.some((p) => p.parentId == null || p.parentId === 0)) {
      return perms
        .filter((p) => p.parentId == null || p.parentId === 0)
        .map<DataNode>((root) => {
          const children = perms
            .filter((c) => c.parentId === root.id)
            .map<DataNode>((leaf) => ({
              title: `${leaf.name} (${leaf.code})`,
              key: leaf.id,
            }));
          return {
            title: root.name,
            key: `module-${root.id}`,
            children,
          };
        });
    }

    const groups = new Map<PermissionType, SysPermission[]>();
    for (const p of perms) {
      const list = groups.get(p.type) ?? [];
      list.push(p);
      groups.set(p.type, list);
    }
    return Array.from(groups.entries()).map<DataNode>(([type, list]) => ({
      title: TYPE_LABEL[type] ?? type,
      key: `module-${type}`,
      children: list.map<DataNode>((p) => ({
        title: `${p.name} (${p.code})`,
        key: p.id,
      })),
    }));
  };

  /**
   * Strip module-string keys out of antd Tree's checkedKeys — the backend
   * wants leaf permission IDs only, so any non-numeric key (we only ever emit
   * `module-…` strings for parents) is dropped here.
   */
  const toLeafIds = (keys: Array<number | string>): number[] =>
    keys.filter((k): k is number => typeof k === 'number');

  const openAssignModal = async (role: SysRole) => {
    setAssigningRole(role);
    setAssignOpen(true);
    setAssignLoading(true);
    setAssignTree([]);
    setAssignChecked([]);
    try {
      const [allRes, assignedRes] = await Promise.all([
        request.get('/permissions') as unknown as Promise<SysPermission[] | null>,
        request.get(`/roles/${role.id}/permissions`) as unknown as Promise<number[] | null>,
      ]);
      setAssignTree(buildPermissionTree(allRes ?? []));
      setAssignChecked(assignedRes ?? []);
    } catch {
      // axios interceptor surfaces the error toast; close the modal so the
      // operator isn't left staring at an empty tree.
      setAssignOpen(false);
      setAssigningRole(null);
    } finally {
      setAssignLoading(false);
    }
  };

  const closeAssignModal = () => {
    setAssignOpen(false);
    setAssigningRole(null);
    setAssignTree([]);
    setAssignChecked([]);
  };

  const saveAssign = async () => {
    if (!assigningRole) return;
    setAssignSubmitting(true);
    try {
      await request.put(`/roles/${assigningRole.id}/permissions`, assignChecked);
      message.success('权限分配成功');
      closeAssignModal();
      reload();
    } catch {
      // axios interceptor already shows the error notification.
    } finally {
      setAssignSubmitting(false);
    }
  };

  // Memoize so the Tree doesn't re-walk the treeData on every keystroke.
  const assignTreeData = useMemo(() => assignTree, [assignTree]);

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
      width: 240,
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
        <Button
          key="assign"
          type="link"
          size="small"
          disabled={!access.canRead('role:assign-permission')}
          onClick={() => openAssignModal(record)}
        >
          分配权限
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

      <Modal
        title={`分配权限 — ${assigningRole?.name ?? ''}`}
        open={assignOpen}
        onCancel={closeAssignModal}
        onOk={saveAssign}
        confirmLoading={assignSubmitting}
        okText="保存"
        cancelText="取消"
        width={600}
        destroyOnClose
        maskClosable={false}
      >
        <Spin spinning={assignLoading}>
          <Tree
            checkable
            defaultExpandAll
            checkedKeys={assignChecked}
            onCheck={(keys) => setAssignChecked(toLeafIds(keys as Array<number | string>))}
            treeData={assignTreeData}
          />
        </Spin>
      </Modal>
    </>
  );
}
