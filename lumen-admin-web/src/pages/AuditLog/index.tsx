/**
 * Lumen Admin — Audit Log page.
 *
 * Lists audit rows from `/api/v1/audit-logs` (paged). Records are
 * produced by {@code AuditLogAspect} writing to {@code sys_audit_log}
 * whenever a controller method annotated with `@Audit` runs.
 *
 * Filters: keyword (LIKE across traceId / username / action / uri),
 * status (1=成功 / 0=失败), resource (精确匹配)。
 *
 * Toolbar status filter — renders alongside the table's built-in search
 * panel so power users keep the query form while operators can quickly
 * narrow to failures via the dropdown.
 */

import type { ActionType, ProColumns } from '@ant-design/pro-components';
import { ProCard, ProTable } from '@ant-design/pro-components';
import { Select } from 'antd';
import { useRef, useState } from 'react';
import { IdColumn } from '@/components/columns/IdColumn';
import { request } from '@/services/request';
import type { PageResult, SysAuditLog } from '@/types/api';

const STATUS_ENUM = {
  1: { text: '成功', status: 'Success' as const },
  0: { text: '失败', status: 'Error' as const },
};

const STATUS_FILTER_OPTIONS = [
  { value: '', label: '全部状态' },
  { value: 1, label: '成功' },
  { value: 0, label: '失败' },
] as const;

export const COLUMNS: ProColumns<SysAuditLog>[] = [
  {
    title: 'TraceId',
    dataIndex: 'traceId',
    width: 260,
    render: (_, record) => <IdColumn value={record.traceId} />,
  },
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
  const actionRef = useRef<ActionType>();
  // '' === no filter; 1 === success only; 0 === failure only. The
  // controller treats `status=null` as "all", so we strip the empty
  // string before sending.
  const [statusFilter, setStatusFilter] = useState<number | ''>('');

  const handleStatusChange = (value: number | '') => {
    setStatusFilter(value);
    // Reset to first page so users don't see an empty page from a stale
    // pagination offset after the result set shrinks.
    actionRef.current?.reloadAndRest?.();
  };

  return (
    <ProCard title="审计日志">
      <ProTable<SysAuditLog>
        actionRef={actionRef}
        headerTitle="审计日志"
        rowKey="id"
        columns={COLUMNS}
        search={{ labelWidth: 'auto' }}
        toolBarRender={() => [
          <Select
            key="status-filter"
            value={statusFilter}
            onChange={handleStatusChange}
            style={{ width: 140 }}
            options={[...STATUS_FILTER_OPTIONS]}
            placeholder="状态"
            allowClear={false}
          />,
        ]}
        pagination={{ defaultPageSize: 20, showSizeChanger: true }}
        request={async (params) => {
          const res = (await request.get('/audit-logs', {
            params: {
              pageNum: params.current,
              pageSize: params.pageSize,
              keyword: params.keyword,
              // Drop the empty-string sentinel so the backend's
              // `required = false` branch fires (no filter).
              status: statusFilter === '' ? undefined : statusFilter,
              resource: params.resource,
            },
          })) as unknown as PageResult<SysAuditLog> | null;
          return { data: res?.records ?? [], total: res?.total ?? 0, success: true };
        }}
      />
    </ProCard>
  );
}