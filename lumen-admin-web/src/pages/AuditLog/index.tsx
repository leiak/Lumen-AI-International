/**
 * Lumen Admin — Audit Log page.
 *
 * Known backend gap: no queryable audit-log endpoint exists yet.
 * `AuditLogService` writes records (via the `@Audit` annotation on
 * controllers), but there is no `SysAuditLogController` exposing
 * `/api/v1/audit-logs`. Until that lands, this page renders an honest
 * "not yet implemented" placeholder rather than a broken table.
 *
 * Tech-debt ticket: implement `SysAuditLogController#page` and
 * `SysAuditLogRepository` (paged, filterable by traceId / userId /
 * dateRange) — then swap the placeholder body for the ProTable block
 * that lives below the gap comment.
 */

import type { ProColumns } from '@ant-design/pro-components';
import { ProCard } from '@ant-design/pro-components';
import { Empty, Typography } from 'antd';
import type { SysAuditLog } from '@/types/api';

const STATUS_ENUM = {
  1: { text: '成功', status: 'Success' as const },
  0: { text: '失败', status: 'Error' as const },
};

export const COLUMNS: ProColumns<SysAuditLog>[] = [
  { title: 'TraceId', dataIndex: 'traceId', width: 220, copyable: true },
  { title: '用户', dataIndex: 'username', width: 140 },
  { title: '动作', dataIndex: 'action', width: 160 },
  { title: '资源', dataIndex: 'resource', width: 140 },
  { title: 'URI', dataIndex: 'uri', ellipsis: true },
  {
    title: '状态',
    dataIndex: 'status',
    width: 100,
    valueEnum: STATUS_ENUM,
    valueType: 'select',
  },
  { title: '耗时 (ms)', dataIndex: 'costMs', width: 120, hideInSearch: true },
  {
    title: '时间',
    dataIndex: 'createdAt',
    valueType: 'dateTime',
    width: 180,
    hideInSearch: true,
  },
];

export default function AuditLog() {
  return (
    <ProCard title="审计日志">
      <Empty
        description={
          <Typography.Text type="secondary">
            Audit log query endpoint not yet implemented
            <br />
            （后端 <code>SysAuditLogController</code> 尚未提供，详见页面顶部注释）
          </Typography.Text>
        }
      />

      {/* When the backend adds GET /api/v1/audit-logs, replace the
          <Empty> placeholder above with a real <ProTable>. The
          COLUMNS constant and request shape are already defined
          below for reference. */}
    </ProCard>
  );
}
