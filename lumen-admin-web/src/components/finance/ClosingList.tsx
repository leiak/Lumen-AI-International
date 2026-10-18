/**
 * Lumen Admin — Finance monthly-closing list (Iteration 4.4 PR3).
 *
 * Workbench for the month-end closing state machine:
 *
 *   OPEN         → 开始月结 (calls start())
 *   IN_PROGRESS  → 完成月结 (calls close())
 *   CLOSED       → 重开     (calls reopen() with reason ≥ 10 chars)
 *
 * Perms (V30 RBAC seed, 4.4 PR1-extension):
 *   `finance:closing:view`   — read access (column visibility / list load)
 *   `finance:closing:manage` — start / close / reopen (high-sensitivity)
 *
 * Status colours:
 *   OPEN         — default
 *   IN_PROGRESS  — gold
 *   CLOSED       — green
 *
 * Backend contract: `BillingClosingController.list/get/start/close/reopen`.
 * Service wrapper: `services/finance/financeClosingService`.
 */

import type { ActionType, ProColumns } from '@ant-design/pro-components';
import { ProTable } from '@ant-design/pro-components';
import { App as AntApp, Button, Form, Input, Modal, Space, Tag } from 'antd';
import { useRef, useState } from 'react';

import { DateTimeColumn } from '@/components/columns/DateTimeColumn';
import { useAccess } from '@/hooks/useAccess';
import {
  financeClosingService,
  type BillingMonthlyClosing,
  type ClosingStatus,
} from '@/services/finance/financeClosingService';

const STATUS_COLOR: Record<ClosingStatus, string> = {
  OPEN: 'default',
  IN_PROGRESS: 'gold',
  CLOSED: 'green',
};

const STATUS_LABEL: Record<ClosingStatus, string> = {
  OPEN: '未开始',
  IN_PROGRESS: '进行中',
  CLOSED: '已关闭',
};

const STATUS_OPTIONS: { value: ClosingStatus; label: string }[] = [
  { value: 'OPEN', label: '未开始' },
  { value: 'IN_PROGRESS', label: '进行中' },
  { value: 'CLOSED', label: '已关闭' },
];

interface ReopenFormValues {
  reason: string;
}

/**
 * Monthly-closing workbench — ProTable with workflow action buttons.
 * Page wrapper at `pages/Finance/Closing/List.tsx` re-exports this so the
 * tests in `components/finance/__tests__/` can target it without reaching
 * into the page subtree.
 */
export default function ClosingList() {
  const actionRef = useRef<ActionType>();
  const { message: antMessage } = AntApp.useApp();
  const access = useAccess();
  const [reopenOpen, setReopenOpen] = useState(false);
  const [reopenPeriod, setReopenPeriod] = useState<string | null>(null);
  const [reopenForm] = Form.useForm<ReopenFormValues>();

  const handleStart = async (period: string) => {
    try {
      await financeClosingService.start({ period });
      antMessage.success('月结已开始');
      actionRef.current?.reload();
    } catch {
      /* handled by axios interceptor */
    }
  };

  const handleClose = async (period: string) => {
    try {
      await financeClosingService.close({ period });
      antMessage.success('月结已完成');
      actionRef.current?.reload();
    } catch {
      /* handled */
    }
  };

  const openReopen = (period: string) => {
    setReopenPeriod(period);
    reopenForm.resetFields();
    setReopenOpen(true);
  };

  const handleReopen = async () => {
    try {
      const vals = await reopenForm.validateFields();
      if (reopenPeriod == null) return;
      await financeClosingService.reopen({ period: reopenPeriod, reason: vals.reason.trim() });
      antMessage.success('月结已重开');
      setReopenOpen(false);
      reopenForm.resetFields();
      actionRef.current?.reload();
    } catch {
      /* handled */
    }
  };

  const columns: ProColumns<BillingMonthlyClosing>[] = [
    { title: '期间', dataIndex: 'period', width: 110 },
    {
      title: '状态',
      dataIndex: 'status',
      width: 110,
      valueType: 'select',
      fieldProps: { options: STATUS_OPTIONS },
      render: (_, r) => (
        <Tag color={STATUS_COLOR[r.status as ClosingStatus] ?? 'default'}>
          {STATUS_LABEL[r.status as ClosingStatus] ?? r.status}
        </Tag>
      ),
    },
    {
      title: '快照发票合计',
      dataIndex: 'snapshotTotalInvoice',
      width: 140,
      hideInSearch: true,
      render: (_, r) => (r.snapshotTotalInvoice != null ? r.snapshotTotalInvoice.toFixed(2) : '—'),
    },
    {
      title: '快照 accrual',
      dataIndex: 'snapshotTotalAccrual',
      width: 140,
      hideInSearch: true,
      render: (_, r) => (r.snapshotTotalAccrual != null ? r.snapshotTotalAccrual.toFixed(2) : '—'),
    },
    {
      title: '快照调账',
      dataIndex: 'snapshotTotalAdjustment',
      width: 130,
      hideInSearch: true,
      render: (_, r) =>
        r.snapshotTotalAdjustment != null ? r.snapshotTotalAdjustment.toFixed(2) : '—',
    },
    {
      title: '快照币种',
      dataIndex: 'snapshotCurrency',
      width: 100,
      hideInSearch: true,
      render: (_, r) => r.snapshotCurrency ?? '—',
    },
    {
      title: '快照重算时间',
      dataIndex: 'snapshotRecalculatedAt',
      width: 180,
      hideInSearch: true,
      render: (_, r) => <DateTimeColumn value={r.snapshotRecalculatedAt} />,
    },
    {
      title: '关闭时间',
      dataIndex: 'closedAt',
      width: 180,
      hideInSearch: true,
      render: (_, r) => <DateTimeColumn value={r.closedAt} />,
    },
    {
      title: '操作',
      valueType: 'option',
      width: 240,
      render: (_, record) => (
        <Space size="small" wrap>
          {record.status === 'OPEN' && (
            <Button
              key="start"
              type="link"
              size="small"
              disabled={!access.canRead('finance:closing:manage')}
              onClick={() => handleStart(record.period)}
            >
              开始月结
            </Button>
          )}
          {record.status === 'IN_PROGRESS' && (
            <Button
              key="close"
              type="link"
              size="small"
              disabled={!access.canRead('finance:closing:manage')}
              onClick={() => handleClose(record.period)}
            >
              完成月结
            </Button>
          )}
          {record.status === 'CLOSED' && (
            <Button
              key="reopen"
              type="link"
              size="small"
              disabled={!access.canRead('finance:closing:manage')}
              onClick={() => openReopen(record.period)}
            >
              重开
            </Button>
          )}
        </Space>
      ),
    },
  ];

  return (
    <>
      <ProTable<BillingMonthlyClosing>
        headerTitle="财务月结"
        actionRef={actionRef}
        rowKey="id"
        columns={columns}
        search={{ labelWidth: 'auto' }}
        request={async (params) => {
          const res = await financeClosingService.list({
            page: params.current ?? 1,
            size: params.pageSize ?? 20,
          });
          return { data: res.records, total: res.total, success: true };
        }}
        pagination={{ defaultPageSize: 20, showSizeChanger: true }}
      />

      <Modal
        title="重开月结"
        open={reopenOpen}
        onOk={handleReopen}
        onCancel={() => {
          setReopenOpen(false);
          reopenForm.resetFields();
        }}
        okText="确认重开"
        cancelText="取消"
        destroyOnClose
        width={520}
      >
        <p>
          期间: <strong>{reopenPeriod ?? '—'}</strong>
        </p>
        <Form<ReopenFormValues> form={reopenForm} layout="vertical">
          <Form.Item
            name="reason"
            label="原因 (至少 10 字符)"
            rules={[
              { required: true, message: '请填写原因' },
              { min: 10, message: '原因至少 10 字符' },
            ]}
          >
            <Input.TextArea rows={3} placeholder="例如: L2 终审后发现错误纠正" />
          </Form.Item>
        </Form>
      </Modal>
    </>
  );
}