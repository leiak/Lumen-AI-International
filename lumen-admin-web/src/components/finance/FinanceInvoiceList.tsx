/**
 * Lumen Admin — Finance invoice list (Iteration 4.3.1 PR4).
 *
 * Receivable / Payable invoice workbench. Includes a Tabs strip
 * (应收 / 应付) and the L1 approval actions (提交初审 / 通过 / 驳回)
 * surfaced conditionally based on the row's `status`.
 *
 * Status colors (per plan):
 *   DRAFT        — default (草稿)
 *   PENDING_L1   — gold     (待 1 级审批)
 *   L1_APPROVED  — green    (L1 已通过)
 *   PUSHED       — blue     (已推送)
 *   REJECTED     — red      (已驳回)
 *
 * Backend contract: `BillingInvoiceController.list/get/submit/l1-approve/l1-reject`.
 * Service wrapper: `services/finance/financeInvoiceService`.
 */

import type { ActionType, ProColumns } from '@ant-design/pro-components';
import { ProTable } from '@ant-design/pro-components';
import {
  App as AntApp,
  Button,
  Modal,
  Space,
  Tabs,
  Tag,
} from 'antd';
import { useRef, useState } from 'react';

import { DateTimeColumn } from '@/components/columns/DateTimeColumn';
import { useAccess } from '@/hooks/useAccess';
import {
  financeInvoiceService,
  type BillingInvoice,
  type InvoiceStatus,
} from '@/services/finance/financeInvoiceService';

const STATUS_OPTIONS = [
  { value: 'DRAFT',       label: '草稿' },
  { value: 'PENDING_L1',  label: '待初审' },
  { value: 'L1_APPROVED', label: 'L1 已通过' },
  { value: 'PUSHED',      label: '已推送' },
  { value: 'REJECTED',    label: '已驳回' },
];

const STATUS_COLOR: Record<InvoiceStatus, string> = {
  DRAFT: 'default',
  PENDING_L1: 'gold',
  L1_APPROVED: 'green',
  PUSHED: 'blue',
  REJECTED: 'red',
};

const STATUS_LABEL: Record<InvoiceStatus, string> = {
  DRAFT: '草稿',
  PENDING_L1: '待初审',
  L1_APPROVED: 'L1 已通过',
  PUSHED: '已推送',
  REJECTED: '已驳回',
};

export interface FinanceInvoiceListProps {
  /**
   * Direction tab preselected on mount. Defaults to 'RECEIVABLE'.
   * Page consumers can omit and let the component manage its own state.
   */
  initialDirection?: 'RECEIVABLE' | 'PAYABLE';
}

/**
 * Receivable/Payable invoice workbench — Tabs on top, ProTable below.
 * The page wrapper at `pages/Finance/Invoices/List.tsx` just re-exports
 * this so the tests in `components/finance/__tests__/` can target it
 * without reaching into the page subtree.
 */
export default function FinanceInvoiceList({
  initialDirection = 'RECEIVABLE',
}: FinanceInvoiceListProps) {
  const actionRef = useRef<ActionType>();
  const { message: antMessage } = AntApp.useApp();
  const { modal } = AntApp.useApp();
  const access = useAccess();

  // Direction tab state. Use a controlled Tabs so the column filter
  // re-issues a request when the user flips direction.
  const [direction, setDirection] = useState<'RECEIVABLE' | 'PAYABLE'>(initialDirection);

  const handleSubmit = async (id: number) => {
    try {
      await financeInvoiceService.submit(id);
      antMessage.success('已提交初审');
      actionRef.current?.reload();
    } catch {
      // axios interceptor already surfaces the failure notification.
    }
  };

  const handleApprove = async (id: number) => {
    try {
      await financeInvoiceService.l1Approve(id);
      antMessage.success('已审核通过');
      actionRef.current?.reload();
    } catch {
      /* handled */
    }
  };

  const handleReject = (id: number) => {
    let reason = '';
    modal.confirm({
      title: '驳回原因',
      content: (
        <textarea
          data-testid="reject-reason-input"
          rows={3}
          style={{ width: '100%', padding: 8, marginTop: 8 }}
          placeholder="请填写驳回原因 (必填)"
          onChange={(e) => { reason = e.target.value; }}
        />
      ),
      okText: '确认驳回',
      cancelText: '取消',
      okButtonProps: { danger: true },
      onOk: async () => {
        if (!reason.trim()) {
          antMessage.error('驳回原因不能为空');
          throw new Error('reason-required');
        }
        await financeInvoiceService.l1Reject(id, reason.trim());
        antMessage.success('已驳回');
        actionRef.current?.reload();
      },
    });
  };

  const columns: ProColumns<BillingInvoice>[] = [
    { title: '单号', dataIndex: 'invoiceNo', width: 200 },
    {
      title: '方向',
      dataIndex: 'direction',
      width: 90,
      valueType: 'select',
      fieldProps: {
        options: [
          { value: 'RECEIVABLE', label: '应收' },
          { value: 'PAYABLE', label: '应付' },
        ],
      },
      render: (_, r) =>
        r.direction === 'RECEIVABLE' ? (
          <Tag color="blue">应收</Tag>
        ) : (
          <Tag color="orange">应付</Tag>
        ),
    },
    {
      title: '业务类型',
      dataIndex: 'businessType',
      width: 130,
      valueType: 'select',
      fieldProps: {
        options: [
          { value: 'LAST_MILE',     label: '末端配送' },
          { value: 'WAREHOUSE_IN',  label: '入库作业' },
          { value: 'WAREHOUSE_OUT', label: '出库作业' },
        ],
      },
    },
    {
      title: '金额',
      dataIndex: 'netAmount',
      width: 160,
      hideInSearch: true,
      render: (_, r) =>
        r.netAmount != null
          ? `${r.netAmount.toFixed(2)} ${r.currency ?? ''}`
          : '—',
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 110,
      valueType: 'select',
      fieldProps: { options: STATUS_OPTIONS },
      render: (_, r) => (
        <Tag color={STATUS_COLOR[r.status]}>{STATUS_LABEL[r.status]}</Tag>
      ),
    },
    {
      title: '创建时间',
      dataIndex: 'createdAt',
      width: 180,
      hideInSearch: true,
      render: (_, r) => <DateTimeColumn value={r.createdAt} />,
    },
    {
      title: '操作',
      valueType: 'option',
      width: 280,
      render: (_, record) => (
        <Space size="small">
          <Button
            key="detail"
            type="link"
            size="small"
            disabled={!access.canRead('finance:invoice:view')}
            onClick={() => handleDetail(record)}
          >
            详情
          </Button>
          {record.status === 'DRAFT' && (
            <Button
              key="submit"
              type="link"
              size="small"
              disabled={!access.canRead('finance:invoice:submit')}
              onClick={() => handleSubmit(record.id)}
            >
              提交初审
            </Button>
          )}
          {record.status === 'PENDING_L1' && (
            <>
              <Button
                key="approve"
                type="link"
                size="small"
                disabled={!access.canRead('finance:invoice:l1_approve')}
                onClick={() => handleApprove(record.id)}
              >
                通过
              </Button>
              <Button
                key="reject"
                type="link"
                size="small"
                danger
                disabled={!access.canRead('finance:invoice:l1_approve')}
                onClick={() => handleReject(record.id)}
              >
                驳回
              </Button>
            </>
          )}
        </Space>
      ),
    },
  ];

  return (
    <div>
      <Tabs
        activeKey={direction}
        onChange={(k) => setDirection(k as 'RECEIVABLE' | 'PAYABLE')}
        items={[
          { key: 'RECEIVABLE', label: '应收单据' },
          { key: 'PAYABLE',     label: '应付单据' },
        ]}
      />
      <ProTable<BillingInvoice>
        key={direction}
        headerTitle={direction === 'RECEIVABLE' ? '应收单据' : '应付单据'}
        actionRef={actionRef}
        rowKey="id"
        columns={columns}
        search={{ labelWidth: 'auto' }}
        request={async (params) => {
          const res = await financeInvoiceService.list({
            direction,
            status: params.status,
            pageNum: params.current ?? 1,
            pageSize: params.pageSize ?? 20,
          });
          return {
            data: res.records,
            total: res.total,
            success: true,
          };
        }}
        pagination={{ defaultPageSize: 20, showSizeChanger: true }}
      />
    </div>
  );
}

// ---------------------------------------------------------------------------
// Local helpers (kept here so the file is self-contained)
// ---------------------------------------------------------------------------

function handleDetail(record: BillingInvoice) {
  Modal.info({
    title: `单据详情 — ${record.invoiceNo}`,
    content: (
      <div style={{ paddingTop: 8 }}>
        <p>方向: {record.direction === 'RECEIVABLE' ? '应收' : '应付'}</p>
        <p>状态: {STATUS_LABEL[record.status] ?? record.status}</p>
        <p>业务类型: {record.businessType ?? '—'}</p>
        <p>净额: {record.netAmount?.toFixed(2) ?? '—'} {record.currency ?? ''}</p>
        <p>含税: {record.taxAmount?.toFixed(2) ?? '—'} {record.currency ?? ''}</p>
        <p>总额: {record.grossAmount?.toFixed(2) ?? '—'} {record.currency ?? ''}</p>
        <p>创建时间: {record.createdAt ?? '—'}</p>
        {record.errorMsg && <p style={{ color: '#cf1322' }}>错误: {record.errorMsg}</p>}
      </div>
    ),
    okText: '关闭',
  });
}