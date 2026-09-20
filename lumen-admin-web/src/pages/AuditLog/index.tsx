/**
 * Lumen Admin — Audit Log page.
 *
 * Lists audit rows from `/api/v1/audit-logs` (paged). Records are
 * produced by {@code AuditLogAspect} writing to {@code sys_audit_log}
 * whenever a controller method annotated with `@Audit` runs.
 *
 * Filters: keyword (LIKE across traceId / username / action / uri),
 * status (1=成功 / 0=失败), resource (精确匹配)。
 */

import type { ProColumns } from '@ant-design/pro-components';
import { ProCard, ProTable } from '@ant-design/pro-components';
import { request } from '@/services/request';
import type { PageResult, SysAuditLog } from '@/types/api';

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
      <ProTable<SysAuditLog>
        headerTitle="审计日志"
        rowKey="id"
        columns={COLUMNS}
        search={{ labelWidth: 'auto' }}
        pagination={{ defaultPageSize: 20, showSizeChanger: true }}
        request={async (params) => {
          const res = (await request.get('/audit-logs', {
            params: {
              pageNum: params.current,
              pageSize: params.pageSize,
              keyword: params.keyword,
              status: params.status,
              resource: params.resource,
            },
          })) as unknown as PageResult<SysAuditLog> | null;
          return { data: res?.records ?? [], total: res?.total ?? 0, success: true };
        }}
      />
    </ProCard>
  );
}
