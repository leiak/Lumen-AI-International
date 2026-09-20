/**
 * Lumen Admin — Permissions page (matrix view).
 *
 * Replaces the previous flat list with a 2-D matrix:
 *   - Rows: every permission (GET /api/v1/permissions).
 *   - Columns: every role (GET /api/v1/roles?pageSize=1000).
 *   - Cells: checkboxes reflecting role↔permission membership, seeded from
 *     a per-role GET /api/v1/roles/{id}/permissions round-trip.
 *
 * Toggling a cell PUTs the *full* new perm-ID set to
 * /api/v1/roles/{id}/permissions — the endpoint replaces, it does not
 * patch. We optimistically reflect the change locally and roll back on
 * failure so the operator doesn't see a flash of stale state.
 *
 * Permission gates:
 *   - `permission:list`              — see the matrix at all.
 *   - `role:assign-permission`       — toggle individual cells.
 *
 * Performance notes:
 *   - Initial load = 2 + N parallel GETs (N = #roles). Typical seed has
 *     ≤20 roles, so this is fine. If N grows past ~50 we should add a
 *     bulk endpoint on the backend; out of scope for now.
 *   - Disabled roles are filtered out of the column list by default;
 *     toggle "显示已禁用" to include them.
 *   - Keyword search filters rows by perm name / code (case-insensitive).
 */

import type { ColumnsType } from 'antd/es/table';
import {
  App,
  Checkbox,
  Empty,
  Input,
  Space,
  Spin,
  Switch,
  Table,
  Tag,
  Tooltip,
  Typography,
} from 'antd';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { useAccess } from '@/hooks/useAccess';
import { request } from '@/services/request';
import type {
  PageResult,
  PermissionType,
  SysPermission,
  SysRole,
} from '@/types/api';

const TYPE_LABEL: Record<PermissionType, { text: string; color: string }> = {
  MENU: { text: '菜单', color: 'blue' },
  BUTTON: { text: '按钮', color: 'green' },
  API: { text: '接口', color: 'orange' },
};

export default function Permissions() {
  const { message } = App.useApp();
  const access = useAccess();

  // ----- source data -----
  const [roles, setRoles] = useState<SysRole[]>([]);
  const [perms, setPerms] = useState<SysPermission[]>([]);
  /** roleId → set of permIds assigned to that role. */
  const [assignMap, setAssignMap] = useState<Record<number, Set<number>>>({});
  const [loading, setLoading] = useState(true);

  // ----- ui state -----
  const [keyword, setKeyword] = useState('');
  const [showDisabled, setShowDisabled] = useState(false);
  /** Role whose PUT is in flight — disables its column to prevent racing toggles. */
  const [pendingRoleId, setPendingRoleId] = useState<number | null>(null);

  // ----- initial fetch -----
  useEffect(() => {
    let cancelled = false;
    (async () => {
      setLoading(true);
      try {
        const [rolesRes, permsRes] = await Promise.all([
          request.get('/roles', { params: { pageNum: 1, pageSize: 1000 } }) as unknown as
            | PageResult<SysRole>
            | null,
          request.get('/permissions') as unknown as SysPermission[] | null,
        ]);
        if (cancelled) return;

        const roleList = rolesRes?.records ?? [];
        const permList = permsRes ?? [];
        setRoles(roleList);
        setPerms(permList);

        // Fan-out per-role perm fetch. Parallel is fine for typical N (~10–20).
        const entries = await Promise.all(
          roleList.map(async (role) => {
            const ids =
              (await request.get(`/roles/${role.id}/permissions`)) as unknown as
                | number[]
                | null;
            return [role.id, new Set(ids ?? [])] as const;
          }),
        );
        if (cancelled) return;
        setAssignMap(Object.fromEntries(entries));
      } catch {
        // axios interceptor surfaces a toast — nothing else to do here.
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  // ----- derived -----
  const visibleRoles = useMemo(
    () => roles.filter((r) => showDisabled || r.status === 1),
    [roles, showDisabled],
  );

  const filteredPerms = useMemo(() => {
    if (!keyword.trim()) return perms;
    const k = keyword.trim().toLowerCase();
    return perms.filter(
      (p) =>
        p.name.toLowerCase().includes(k) || p.code.toLowerCase().includes(k),
    );
  }, [perms, keyword]);

  // ----- toggle handler -----
  const toggleCell = useCallback(
    async (roleId: number, permId: number) => {
      if (!access.canRead('role:assign-permission')) {
        message.warning('无分配权限的权限');
        return;
      }
      const prev = assignMap[roleId] ?? new Set<number>();
      const next = new Set(prev);
      if (next.has(permId)) next.delete(permId);
      else next.add(permId);

      // Optimistic flip
      setAssignMap((m) => ({ ...m, [roleId]: next }));
      setPendingRoleId(roleId);
      try {
        await request.put(`/roles/${roleId}/permissions`, Array.from(next));
      } catch {
        // Roll back — interceptor already showed the error toast.
        setAssignMap((m) => ({ ...m, [roleId]: prev }));
      } finally {
        setPendingRoleId((cur) => (cur === roleId ? null : cur));
      }
    },
    [assignMap, access, message],
  );

  // ----- columns -----
  const canEdit = access.canRead('role:assign-permission');

  const columns: ColumnsType<SysPermission> = useMemo(() => {
    const roleCols: ColumnsType<SysPermission> = visibleRoles.map((role) => ({
      title: (
        <div
          style={{
            writingMode: 'vertical-rl',
            textOrientation: 'mixed',
            whiteSpace: 'nowrap',
            minWidth: 40,
            padding: '4px 0',
            margin: '0 auto',
            fontSize: 13,
            fontWeight: 500,
          }}
          title={`${role.name} (${role.code})`}
        >
          {role.status === 0 ? (
            <span style={{ color: '#bfbfbf' }}>{role.name}</span>
          ) : (
            role.name
          )}
        </div>
      ),
      dataIndex: `role-${role.id}`,
      key: `role-${role.id}`,
      width: 56,
      align: 'center' as const,
      fixed: visibleRoles.length > 6 ? undefined : ('right' as const),
      render: (_: unknown, record: SysPermission) => {
        const checked = assignMap[role.id]?.has(record.id) ?? false;
        const disabled = pendingRoleId === role.id || !canEdit;
        return (
          <Tooltip
            title={
              !canEdit
                ? '无分配权限的权限'
                : pendingRoleId === role.id
                  ? '保存中…'
                  : `${role.name} / ${record.name}`
            }
          >
            <Checkbox
              checked={checked}
              disabled={disabled}
              onChange={() => toggleCell(role.id, record.id)}
            />
          </Tooltip>
        );
      },
    }));

    return [
      {
        title: (
          <Space size={6}>
            <span>权限</span>
            <Tag color="default">{filteredPerms.length}</Tag>
          </Space>
        ),
        key: 'perm',
        fixed: 'left',
        width: 280,
        render: (_: unknown, record: SysPermission) => (
          <div>
            <Space size={6}>
              <Typography.Text strong>{record.name}</Typography.Text>
              <Tag color={TYPE_LABEL[record.type]?.color}>
                {TYPE_LABEL[record.type]?.text ?? record.type}
              </Tag>
              {record.status === 0 ? <Tag>禁用</Tag> : null}
            </Space>
            <div style={{ fontSize: 12, color: '#8c8c8c', marginTop: 2 }}>
              {record.code}
              {record.path ? ` · ${record.path}` : ''}
            </div>
          </div>
        ),
      },
      ...roleCols,
    ];
  }, [visibleRoles, filteredPerms, assignMap, pendingRoleId, canEdit, toggleCell]);

  if (loading) {
    return (
      <div style={{ padding: 48, textAlign: 'center' }}>
        <Spin />
      </div>
    );
  }

  return (
    <div>
      <Space
        style={{ marginBottom: 16, width: '100%', justifyContent: 'space-between' }}
        wrap
      >
        <Space wrap>
          <Input.Search
            placeholder="搜索权限名称 / 编码"
            allowClear
            style={{ width: 280 }}
            value={keyword}
            onChange={(e) => setKeyword(e.target.value)}
          />
          <Space size={6}>
            <Switch
              size="small"
              checked={showDisabled}
              onChange={setShowDisabled}
            />
            <span style={{ color: '#595959' }}>显示已禁用角色</span>
          </Space>
        </Space>
        <Space size={16} wrap>
          <Typography.Text type="secondary">
            行 = 权限 ({perms.length}) · 列 = 角色 ({visibleRoles.length}/{roles.length})
          </Typography.Text>
        </Space>
      </Space>

      <Table<SysPermission>
        rowKey="id"
        dataSource={filteredPerms}
        columns={columns}
        pagination={false}
        scroll={{ x: 'max-content', y: 600 }}
        size="small"
        bordered
        locale={{
          emptyText: <Empty description="无匹配的权限" />,
        }}
      />
    </div>
  );
}