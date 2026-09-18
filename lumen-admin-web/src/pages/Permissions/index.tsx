/**
 * Lumen Admin — Permissions page.
 *
 * Backend exposes:
 *   GET /api/v1/permissions        → flat SysPermission list (PermissionController#tree)
 *   GET /api/v1/permissions/matrix → Map<roleCode, List<permissionCode>>
 *
 * MVP shows the flat list (cleaner than the matrix for ad-hoc auditing) and
 * exposes a secondary card with the role→permissions map so the "matrix" use
 * case is still discoverable. Both endpoints are read-only — phase 4+ adds
 * the editor.
 */

import type { ProColumns } from '@ant-design/pro-components';
import { ProCard, ProTable } from '@ant-design/pro-components';
import { Tag } from 'antd';
import { request } from '@/services/request';
import type { PermissionMatrix, SysPermission } from '@/types/api';

const TYPE_ENUM = {
  MENU: { text: '菜单', color: 'blue' },
  BUTTON: { text: '按钮', color: 'green' },
  API: { text: '接口', color: 'orange' },
} as const;

const STATUS_ENUM = {
  1: { text: '启用', status: 'Success' as const },
  0: { text: '禁用', status: 'Default' as const },
};

export default function Permissions() {
  const columns: ProColumns<SysPermission>[] = [
    { title: 'ID', dataIndex: 'id', width: 80 },
    { title: '编码', dataIndex: 'code', width: 220 },
    { title: '名称', dataIndex: 'name', width: 200 },
    {
      title: '类型',
      dataIndex: 'type',
      width: 100,
      render: (_, record) => <Tag color={TYPE_ENUM[record.type]?.color}>{TYPE_ENUM[record.type]?.text ?? record.type}</Tag>,
    },
    { title: '路径', dataIndex: 'path', width: 220, ellipsis: true },
    { title: '排序', dataIndex: 'sortOrder', width: 80, hideInSearch: true },
    {
      title: '状态',
      dataIndex: 'status',
      width: 100,
      valueEnum: STATUS_ENUM,
      valueType: 'select',
    },
  ];

  return (
    <ProCard split="vertical">
      <ProTable<SysPermission>
        headerTitle="权限列表"
        rowKey="id"
        columns={columns}
        search={false}
        options={false}
        pagination={{ defaultPageSize: 20 }}
        request={async () => {
          const res = (await request.get('/permissions')) as unknown as
            | SysPermission[]
            | null;
          return { data: res ?? [], success: true };
        }}
      />

      <ProCard title="权限矩阵（角色 → 权限编码）" collapsible defaultCollapsed>
        <ProTable<{ roleCode: string; permCodes: string[] }>
          rowKey="roleCode"
          search={false}
          options={false}
          pagination={false}
          request={async () => {
            const res = (await request.get('/permissions/matrix')) as unknown as
              | PermissionMatrix
              | null;
            const data = Object.entries(res ?? {}).map(([roleCode, permCodes]) => ({
              roleCode,
              permCodes,
            }));
            return { data, success: true };
          }}
          columns={[
            { title: '角色编码', dataIndex: 'roleCode', width: 160 },
            {
              title: '已分配权限',
              dataIndex: 'permCodes',
              render: (_, record) => (
                <>
                  {record.permCodes.map((code) => (
                    <Tag key={code}>{code}</Tag>
                  ))}
                </>
              ),
            },
          ]}
        />
      </ProCard>
    </ProCard>
  );
}
