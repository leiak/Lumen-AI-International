/**
 * Lumen Admin — Finance reconciliation list (Iteration 4.4 PR3).
 *
 * Workbench for period-end reconciliation variances. Toolbar exposes
 * "跑对账" (run reconciliation for a period / currency pair). Row
 * actions:
 *
 *   ADJUSTMENT_CREATED → 调账单详情 (link), 标记已解决, 豁免
 *
 * Per-action perms (V30 RBAC seed, 4.4 PR2-extension):
 *   `finance:reconciliation:run`     — run
 *   `finance:reconciliation:view`    — list
 *   `finance:reconciliation:resolve` — resolve
 *   `finance:reconciliation:waive`   — waive (requires reason ≥ 10 chars)
 *
 * Status colours:
 *   ADJUSTMENT_CREATED — gold
 *   RESOLVED           — green
 *   WAIVED             — default
 *
 * Backend contract: `BillingReconciliationController.run/list/resolve/waive`.
 * Service wrapper: `services/finance/financeReconciliationService`.
 */

import type { ActionType, ProColumns } from '@ant-design/pro-components';
import { ProTable } from '@ant-design/pro-components';
import {
  App as AntApp,
  Button,
  Form,
  Input,
  Modal,
  Select,
  Space,
  Tag,
} from 'antd';
import { useRef, useState } from 'react';

import { DateTimeColumn } from '@/components/columns/DateTimeColumn';
import { useAccess } from '@/hooks/useAccess';
import {
  financeReconciliationService,
  type BillingReconciliation,
  type ReconciliationStatus,
} from '@/services/finance/financeReconciliationService';

const PERIOD_PATTERN = /^\d{4}-(0[1-9]|1[0-2])$/;

const STATUS_COLOR: Record<ReconciliationStatus, string> = {
  ADJUSTMENT_CREATED: 'gold',
  RESOLVED: 'green',
  WAIVED: 'default',
};

const STATUS_LABEL: Record<ReconciliationStatus, string> = {
  ADJUSTMENT_CREATED: '已生成调账单',
  RESOLVED: '已解决',
  WAIVED: '已豁免',
};

const STATUS_OPTIONS: { value: ReconciliationStatus; label: string }[] = [
  { value: 'ADJUSTMENT_CREATED', label: '已生成调账单' },
  { value: 'RESOLVED', label: '已解决' },
  { value: 'WAIVED', label: '已豁免' },
];

interface RunFormValues {
  period: string;
  currency: string;
}

interface WaiveFormValues {
  waiveReason: string;
}

/**
 * Reconciliation workbench — ProTable with "跑对账" toolbar + per-row
 * resolve / waive actions. Page wrapper at
 * `pages/Finance/Reconciliation/List.tsx` re-exports this so tests can
 * target it without reaching into the page subtree.
 */
export default function ReconciliationList() {
  const actionRef = useRef<ActionType>();
  const { message: antMessage } = AntApp.useApp();
  const access = useAccess();

  // Filter state — required `period` (per backend contract).
  const [filterPeriod, setFilterPeriod] = useState<string>(
    () => new Date().toISOString().slice(0, 7),
  );

  // Run modal state.
  const [runOpen, setRunOpen] = useState(false);
  const [runForm] = Form.useForm<RunFormValues>();

  // Waive modal state.
  const [waiveOpen, setWaiveOpen] = useState(false);
  const [waiveRecord, setWaiveRecord] = useState<BillingReconciliation | null>(null);
  const [waiveForm] = Form.useForm<WaiveFormValues>();

  const handleRun = async () => {
    try {
      const vals = await runForm.validateFields();
      await financeReconciliationService.run({
        period: vals.period,
        currency: vals.currency,
      });
      antMessage.success('对账已执行');
      setRunOpen(false);
      runForm.resetFields();
      setFilterPeriod(vals.period);
      actionRef.current?.reload();
    } catch {
      /* handled */
    }
  };

  const handleResolve = async (record: BillingReconciliation) => {
    try {
      await financeReconciliationService.resolve(record.id);
      antMessage.success('已标记为已解决');
      actionRef.current?.reload();
    } catch {
      /* handled */
    }
  };

  const openWaive = (record: BillingReconciliation) => {
    setWaiveRecord(record);
    waiveForm.resetFields();
    setWaiveOpen(true);
  };

  const handleWaive = async () => {
    try {
      const vals = await waiveForm.validateFields();
      if (waiveRecord == null) return;
      await financeReconciliationService.waive(waiveRecord.id, vals.waiveReason.trim());
      antMessage.success('已豁免');
      setWaiveOpen(false);
      waiveForm.resetFields();
      actionRef.current?.reload();
    } catch {
      /* handled */
    }
  };

  const columns: ProColumns<BillingReconciliation>[] = [
    { title: '期间', dataIndex: 'period', width: 110 },
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
          <span style={{ color: '#1677ff' }}>应收</span>
        ) : (
          <span style={{ color: '#fa8c16' }}>应付</span>
        ),
    },
    { title: '对手方 ID', dataIndex: 'counterpartyId', width: 110 },
    {
      title: '币种',
      dataIndex: 'currency',
      width: 90,
      valueType: 'select',
      fieldProps: {
        options: [
          { value: 'CNY', label: 'CNY' },
          { value: 'USD', label: 'USD' },
        ],
      },
    },
    {
      title: '应收/应付',
      dataIndex: 'expectedAmount',
      width: 130,
      hideInSearch: true,
      render: (_, r) => (r.expectedAmount != null ? r.expectedAmount.toFixed(2) : '—'),
    },
    {
      title: '实收/实付',
      dataIndex: 'actualAmount',
      width: 130,
      hideInSearch: true,
      render: (_, r) => (r.actualAmount != null ? r.actualAmount.toFixed(2) : '—'),
    },
    {
      title: '差异',
      dataIndex: 'varianceAmount',
      width: 130,
      hideInSearch: true,
      render: (_, r) => {
        if (r.varianceAmount == null) return '—';
        const v = r.varianceAmount;
        return (
          <span style={{ color: v !== 0 ? '#cf1322' : undefined }}>
            {v.toFixed(2)}
          </span>
        );
      },
    },
    {
      title: '状态',
      dataIndex: 'reconciliationStatus',
      width: 130,
      valueType: 'select',
      fieldProps: { options: STATUS_OPTIONS },
      render: (_, r) => (
        <Tag color={STATUS_COLOR[r.reconciliationStatus as ReconciliationStatus] ?? 'default'}>
          {STATUS_LABEL[r.reconciliationStatus as ReconciliationStatus] ?? r.reconciliationStatus}
        </Tag>
      ),
    },
    {
      title: '关联调账单',
      dataIndex: 'adjustmentId',
      width: 130,
      hideInSearch: true,
      render: (_, r) =>
        r.adjustmentId != null ? (
          <a
            href={`/finance/adjustments`}
            onClick={(e) => {
              e.preventDefault();
              window.location.assign(`/finance/adjustments`);
            }}
          >
            #{r.adjustmentId}
          </a>
        ) : (
          '—'
        ),
    },
    {
      title: '备注',
      dataIndex: 'remark',
      width: 200,
      hideInSearch: true,
      ellipsis: true,
    },
    {
      title: '更新时间',
      dataIndex: 'updatedAt',
      width: 180,
      hideInSearch: true,
      render: (_, r) => <DateTimeColumn value={r.updatedAt} />,
    },
    {
      title: '操作',
      valueType: 'option',
      width: 240,
      render: (_, record) => (
        <Space size="small" wrap>
          {record.adjustmentId != null && (
            <Button
              key="adjustment-link"
              type="link"
              size="small"
              onClick={() => {
                window.location.assign(`/finance/adjustments`);
              }}
            >
              调账单详情
            </Button>
          )}
          {record.reconciliationStatus === 'ADJUSTMENT_CREATED' && (
            <>
              <Button
                key="resolve"
                type="link"
                size="small"
                disabled={!access.canRead('finance:reconciliation:resolve')}
                onClick={() => handleResolve(record)}
              >
                标记已解决
              </Button>
              <Button
                key="waive"
                type="link"
                size="small"
                danger
                disabled={!access.canRead('finance:reconciliation:waive')}
                onClick={() => openWaive(record)}
              >
                豁免
              </Button>
            </>
          )}
        </Space>
      ),
    },
  ];

  return (
    <>
      <ProTable<BillingReconciliation>
        headerTitle="对账差异"
        actionRef={actionRef}
        rowKey="id"
        columns={columns}
        search={false}
        request={async (params) => {
          if (!filterPeriod) {
            return { data: [], total: 0, success: true };
          }
          const res = await financeReconciliationService.list({
            period: filterPeriod,
            direction: params.direction,
            status: params.reconciliationStatus,
          });
          return { data: res.records, total: res.total, success: true };
        }}
        pagination={{ defaultPageSize: 20, showSizeChanger: true }}
        toolBarRender={() => [
          <Select
            key="period-filter"
            value={filterPeriod}
            onChange={(v) => {
              setFilterPeriod(v);
              actionRef.current?.reload();
            }}
            style={{ width: 160 }}
            options={[
              { value: '2026-10', label: '2026-10' },
              { value: '2026-09', label: '2026-09' },
              { value: '2026-08', label: '2026-08' },
              { value: '2026-07', label: '2026-07' },
            ]}
            placeholder="期间"
          />,
          <Button
            key="run"
            type="primary"
            disabled={!access.canRead('finance:reconciliation:run')}
            onClick={() => {
              runForm.resetFields();
              runForm.setFieldsValue({ period: filterPeriod, currency: 'CNY' });
              setRunOpen(true);
            }}
          >
            跑对账
          </Button>,
        ]}
      />

      {/* Run modal — period + currency */}
      <Modal
        title="跑对账"
        open={runOpen}
        onOk={handleRun}
        onCancel={() => {
          setRunOpen(false);
          runForm.resetFields();
        }}
        okText="开始"
        cancelText="取消"
        destroyOnClose
        width={480}
      >
        <Form<RunFormValues> form={runForm} layout="vertical">
          <Form.Item
            name="period"
            label="期间 (YYYY-MM)"
            rules={[
              { required: true, message: '请输入期间' },
              { pattern: PERIOD_PATTERN, message: '格式 YYYY-MM (例 2026-10)' },
            ]}
          >
            <Input placeholder="2026-10" />
          </Form.Item>
          <Form.Item
            name="currency"
            label="币种"
            rules={[{ required: true, message: '请选择币种' }]}
          >
            <Select
              options={[
                { value: 'CNY', label: 'CNY' },
                { value: 'USD', label: 'USD' },
              ]}
              placeholder="请选择"
            />
          </Form.Item>
        </Form>
      </Modal>

      {/* Waive modal — reason ≥ 10 chars */}
      <Modal
        title="豁免对账差异"
        open={waiveOpen}
        onOk={handleWaive}
        onCancel={() => {
          setWaiveOpen(false);
          waiveForm.resetFields();
        }}
        okText="确认豁免"
        cancelText="取消"
        okButtonProps={{ danger: true }}
        destroyOnClose
        width={520}
      >
        {waiveRecord && (
          <p>
            期间: <strong>{waiveRecord.period}</strong> · 对手方 #
            {waiveRecord.counterpartyId} · 差异{' '}
            <strong>{waiveRecord.varianceAmount?.toFixed(2) ?? '—'}</strong>
          </p>
        )}
        <Form<WaiveFormValues> form={waiveForm} layout="vertical">
          <Form.Item
            name="waiveReason"
            label="豁免原因 (至少 10 字符)"
            rules={[
              { required: true, message: '请填写豁免原因' },
              { min: 10, message: '原因至少 10 字符' },
            ]}
          >
            <Input.TextArea rows={3} placeholder="例如: 业务确认小额差异可豁免" />
          </Form.Item>
        </Form>
      </Modal>
    </>
  );
}