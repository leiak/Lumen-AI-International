/**
 * Lumen Admin — Finance invoice list (Iteration 4.3.1 PR4 + 4.3.2 PR5).
 *
 * Receivable / Payable invoice workbench. Includes a Tabs strip
 * (应收 / 应付) and the L1 + L2 approval actions (提交初审 / 通过 / 驳回)
 * surfaced conditionally based on the row's `status`.
 *
 * Status colors (per plan):
 *   DRAFT        — default (草稿)
 *   PENDING_L1   — gold     (待初审)
 *   PENDING_L2   — orange   (待终审)
 *   L1_APPROVED  — green    (L1 已通过 — historical)
 *   L2_APPROVED  — cyan     (L2 已通过)
 *   PUSHED       — blue     (已推送)
 *   REJECTED     — red      (已驳回)
 *
 * Iteration 4.3.2 PR5 changes:
 *   - Added 币种 column.
 *   - Wired L2 approve / reject buttons via shared
 *     `<L2ApprovalButtons />` (l2_approve perm + SoD guard). Buttons
 *     appear in BOTH the action column AND inside the detail drawer
 *     so the operator can pick either surface.
 *   - Added L2 approve / reject endpoints to `financeInvoiceService`.
 *   - Added PENDING_L2 / L2_APPROVED to `InvoiceStatus` enum.
 *
 * Backend contract: `BillingInvoiceController.list/get/submit/l1-approve/
 *  l1-reject/l2-approve/l2-reject`.
 * Service wrapper: `services/finance/financeInvoiceService`.
 */

import type { ActionType, ProColumns } from '@ant-design/pro-components';
import { ProTable } from '@ant-design/pro-components';
import {
  App as AntApp,
  Button,
  Descriptions,
  Drawer,
  Space,
  Tabs,
  Tag,
} from 'antd';
import { useRef, useState } from 'react';

import { DateTimeColumn } from '@/components/columns/DateTimeColumn';
import L2ApprovalButtons from '@/components/finance/L2ApprovalButtons';
import { useAccess } from '@/hooks/useAccess';
import { useAuth } from '@/auth/useAuth';
import {
  financeInvoiceService,
  type BillingInvoice,
  type InvoiceStatus,
} from '@/services/finance/financeInvoiceService';

const STATUS_OPTIONS = [
  { value: 'DRAFT',       label: '草稿' },
  { value: 'PENDING_L1',  label: '待初审' },
  { value: 'PENDING_L2',  label: '待终审' },
  { value: 'L2_APPROVED', label: 'L2 已通过' },
  { value: 'PUSHED',      label: '已推送' },
  { value: 'REJECTED',    label: '已驳回' },
];

const STATUS_COLOR: Record<InvoiceStatus, string> = {
  DRAFT: 'default',
  PENDING_L1: 'gold',
  PENDING_L2: 'orange',
  L1_APPROVED: 'green',
  L2_APPROVED: 'cyan',
  PUSHED: 'blue',
  REJECTED: 'red',
};

const STATUS_LABEL: Record<InvoiceStatus, string> = {
  DRAFT: '草稿',
  PENDING_L1: '待初审',
  PENDING_L2: '待终审',
  L1_APPROVED: 'L1 已通过',
  L2_APPROVED: 'L2 已通过',
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
  const { user } = useAuth();
  const currentUserId = user?.userId;

  // Direction tab state. Use a controlled Tabs so the column filter
  // re-issues a request when the user flips direction.
  const [direction, setDirection] = useState<'RECEIVABLE' | 'PAYABLE'>(initialDirection);

  // Detail drawer (used to surface L2 buttons inline with full record view).
  const [detailOpen, setDetailOpen] = useState(false);
  const [current, setCurrent] = useState<BillingInvoice | null>(null);

  const openDetail = async (record: BillingInvoice) => {
    setCurrent(record);
    setDetailOpen(true);
    // Best-effort fetch the freshest version (list view sometimes omits L2 fields).
    try {
      const fresh = await financeInvoiceService.get(record.id);
      setCurrent(fresh);
    } catch {
      /* keep the row snapshot */
    }
  };

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

  const handleL2Approve = async (id: number) => {
    try {
      await financeInvoiceService.l2Approve(id);
      antMessage.success('L2 已通过');
      actionRef.current?.reload();
    } catch {
      /* handled */
    }
  };

  const handleL2Reject = async (id: number, comment: string) => {
    await financeInvoiceService.l2Reject(id, comment);
    antMessage.success('已驳回');
    actionRef.current?.reload();
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
      width: 130,
      hideInSearch: true,
      render: (_, r) =>
        r.netAmount != null
          ? `${r.netAmount.toFixed(2)}`
          : '—',
    },
    {
      title: '币种',
      dataIndex: 'currency',
      width: 90,
      valueType: 'select',
      fieldProps: {
        options: [
          { value: 'CNY', label: 'CNY' },
          { value: 'USD', label: 'USD' },
          { value: 'EUR', label: 'EUR' },
        ],
      },
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
      width: 320,
      render: (_, record) => (
        <Space size="small">
          <Button
            key="detail"
            type="link"
            size="small"
            disabled={!access.canRead('finance:invoice:view')}
            onClick={() => openDetail(record)}
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
          {record.status === 'PENDING_L2' && (
            <L2ApprovalButtons
              currentStatus={record.status}
              l1ApproverId={record.l1ApproverId}
              currentUserId={currentUserId}
              perm="finance:invoice:l2_approve"
              onL2Approve={() => handleL2Approve(record.id)}
              onL2Reject={(comment) => handleL2Reject(record.id, comment)}
            />
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

      {/* Detail drawer — shows the full record and exposes L2 buttons when
          status is PENDING_L2. Matches the convention used by the
          adjustment list. */}
      <Drawer
        title={current ? `单据详情 — ${current.invoiceNo}` : '单据详情'}
        open={detailOpen}
        onClose={() => setDetailOpen(false)}
        width={560}
      >
        {current && (
          <Descriptions column={1} bordered size="small">
            <Descriptions.Item label="单号">{current.invoiceNo}</Descriptions.Item>
            <Descriptions.Item label="方向">
              {current.direction === 'RECEIVABLE' ? '应收' : '应付'}
            </Descriptions.Item>
            <Descriptions.Item label="状态">
              <Tag color={STATUS_COLOR[current.status]}>
                {STATUS_LABEL[current.status] ?? current.status}
              </Tag>
            </Descriptions.Item>
            <Descriptions.Item label="业务类型">{current.businessType ?? '—'}</Descriptions.Item>
            <Descriptions.Item label="币种">{current.currency ?? '—'}</Descriptions.Item>
            <Descriptions.Item label="净额">
              {current.netAmount?.toFixed(2) ?? '—'} {current.currency ?? ''}
            </Descriptions.Item>
            <Descriptions.Item label="含税">
              {current.taxAmount?.toFixed(2) ?? '—'} {current.currency ?? ''}
            </Descriptions.Item>
            <Descriptions.Item label="总额">
              {current.grossAmount?.toFixed(2) ?? '—'} {current.currency ?? ''}
            </Descriptions.Item>
            {current.l1ApproverId != null && (
              <Descriptions.Item label="L1 审批">
                #{current.l1ApproverId} · {current.l1ApprovedAt ?? '—'}
                {current.l1Comment ? ` · ${current.l1Comment}` : ''}
              </Descriptions.Item>
            )}
            {current.l2ApproverId != null && (
              <Descriptions.Item label="L2 审批">
                #{current.l2ApproverId} · {current.l2ApprovedAt ?? '—'}
                {current.l2Comment ? ` · ${current.l2Comment}` : ''}
              </Descriptions.Item>
            )}
            <Descriptions.Item label="创建时间">{current.createdAt ?? '—'}</Descriptions.Item>
            {current.errorMsg && (
              <Descriptions.Item label="错误">
                <span style={{ color: '#cf1322' }}>{current.errorMsg}</span>
              </Descriptions.Item>
            )}
            {current.status === 'PENDING_L2' && (
              <Descriptions.Item label="L2 审批操作">
                <L2ApprovalButtons
                  currentStatus={current.status}
                  l1ApproverId={current.l1ApproverId}
                  currentUserId={currentUserId}
                  perm="finance:invoice:l2_approve"
                  onL2Approve={() => handleL2Approve(current.id)}
                  onL2Reject={(comment) => handleL2Reject(current.id, comment)}
                />
              </Descriptions.Item>
            )}
          </Descriptions>
        )}
      </Drawer>
    </div>
  );
}