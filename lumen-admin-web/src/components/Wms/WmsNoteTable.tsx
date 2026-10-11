/**
 * Lumen Admin — WmsNoteTable (PR5-T5.3, Iteration 3.0a).
 *
 * Generic ProTable for WMS note lists (inbound / outbound / adjust).
 * Reduces the three list pages to mostly column configuration: the
 * caller supplies:
 *
 *   - a `service.url` and `service.params` builder for paging
 *   - the no-column renderer (with a link to detail)
 *   - any additional status-tag columns
 *   - the standard list-page chrome (toolbar, title, create button)
 *
 * The actual backend paging call is delegated to the caller because the
 * three endpoints differ in their parameter shapes (inbound/outbound use
 * `keyword` + `status`; adjust uses the same). Keeping the paging logic
 * in the list pages avoids coupling this shared component to a specific
 * URL shape.
 *
 * The component DOES own:
 *   - status select filter (when `statusFilter` is true)
 *   - rowKey + ProColumns wiring + the detail-link column
 *   - the "新建..." toolbar button gated by `newButtonPerm`
 */

import type { ProColumns } from '@ant-design/pro-components';
import { ProTable } from '@ant-design/pro-components';
import { Button } from 'antd';
import type { Key } from 'react';
import { useNavigate } from 'react-router-dom';

import { useAccess } from '@/hooks/useAccess';
import { request } from '@/services/request';
import type { PageResult } from '@/types/api';

export interface WmsNoteTableProps<T extends { id: Key; no: string; status: string }> {
  /** Stable title shown above the table (e.g. '入库单列表'). */
  headerTitle: string;

  /** API path for paging, e.g. '/wms/inbound-notes'. */
  apiPath: string;

  /** All non-no columns. No column itself is composed inside the component. */
  columns: ProColumns<T>[];

  /** Render the note no cell with a link to detail. Optional — default uses `detailPath`. */
  noColumn?: (text: string, record: T) => React.ReactNode;

  /**
   * Optional status select filter — adds an extra column containing only
   * the status filter dropdown (column is hidden in the body row but
   * surfaced in the toolbar via ProTable's search panel).
   */
  statusFilter?: { options: { value: string; label: string }[] };

  /** Optional "新建..." button. */
  newButtonText?: string;
  newButtonPerm?: string;
  newButtonPath?: string;

  /** Detail link builder. */
  detailPath: (record: T) => string;

  /** Optional extra toolbar buttons. */
  extraToolbar?: () => React.ReactNode[];
}

export function WmsNoteTable<T extends { id: Key; no: string; status: string }>({
  headerTitle,
  apiPath,
  columns,
  noColumn,
  statusFilter,
  newButtonText,
  newButtonPerm,
  newButtonPath,
  detailPath,
  extraToolbar,
}: WmsNoteTableProps<T>) {
  const nav = useNavigate();
  const access = useAccess();

  // Compose the no column at index 0; status filter column appended last so
  // it stays in the search area. If the caller omits `noColumn` we provide
  // a default that uses `detailPath` — list pages that want a richer cell
  // (copy button, badge) can override.
  const defaultNoColumn = (text: string, record: T): React.ReactNode => (
    <Button type="link" size="small" onClick={() => nav(detailPath(record))}>
      {text}
    </Button>
  );
  const renderNo = noColumn ?? defaultNoColumn;

  const composed: ProColumns<T>[] = [
    {
      title: '单号',
      dataIndex: 'no',
      width: 200,
      render: (_, r) => renderNo(r.no, r),
    },
    ...columns,
  ];
  if (statusFilter) {
    composed.push({
      title: '状态',
      dataIndex: 'status',
      width: 100,
      valueType: 'select',
      fieldProps: { options: statusFilter.options },
      hideInTable: true,
    });
  }

  const toolbar: React.ReactNode[] = [];
  if (newButtonText && newButtonPerm && newButtonPath) {
    toolbar.push(
      <Button
        key="add"
        type="primary"
        disabled={!access.canRead(newButtonPerm)}
        onClick={() => nav(newButtonPath)}
      >
        {newButtonText}
      </Button>,
    );
  }
  if (extraToolbar) toolbar.push(...extraToolbar());

  return (
    <ProTable<T>
      headerTitle={headerTitle}
      rowKey="id"
      columns={composed}
      search={{ labelWidth: 'auto' }}
      request={async (params) => {
        const res = (await request.get(apiPath, {
          params: {
            pageNum: params.current,
            pageSize: params.pageSize,
            keyword: params.no,
            status: params.status,
          },
        })) as unknown as PageResult<T> | null;
        return {
          data: res?.records ?? [],
          total: res?.total ?? 0,
          success: true,
        };
      }}
      pagination={{ defaultPageSize: 20, showSizeChanger: true }}
      toolBarRender={() => toolbar}
    />
  );
}
