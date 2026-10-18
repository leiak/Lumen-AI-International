/**
 * Lumen Admin — Finance payment-record list (Iteration 4.4 PR3).
 *
 * Receivable / payable (实收/实付) payment-record workbench. Toolbar
 * exposes "新建" (manual entry) and "CSV 导入" (bulk upload).
 *
 * Per-action perms (V30 RBAC seed, 4.4 PR1-extension):
 *   `finance:payment:view` — list + get
 *   `finance:payment:edit` — create + import
 *
 * Backend contract: `BillingPaymentRecordController.list/get/create/import`.
 * Service wrapper: `services/finance/financePaymentService`.
 */

import type { ActionType, ProColumns } from '@ant-design/pro-components';
import { ProTable } from '@ant-design/pro-components';
import {
  App as AntApp,
  Button,
  DatePicker,
  Form,
  Input,
  InputNumber,
  Modal,
  Select,
  Space,
  Upload,
} from 'antd';
import dayjs from 'dayjs';
import { useRef, useState } from 'react';

import { DateTimeColumn } from '@/components/columns/DateTimeColumn';
import { useAccess } from '@/hooks/useAccess';
import {
  financePaymentService,
  type BillingPaymentRecord,
  type CreatePaymentRecordRequest,
} from '@/services/finance/financePaymentService';

const PERIOD_PATTERN = /^\d{4}-(0[1-9]|1[0-2])$/;

interface CreateFormValues {
  period: string;
  direction: 'RECEIVABLE' | 'PAYABLE';
  counterpartyId: number;
  counterpartyType: 'CUSTOMER' | 'CARRIER' | 'WAREHOUSE';
  currency: string;
  amount: number;
  paymentDate: dayjs.Dayjs;
  sourceRef?: string;
  remark?: string;
}

interface ImportFormValues {
  period: string;
  file?: { originFileObj?: File }[];
}

/**
 * Payment-record workbench — ProTable with create + CSV import modals.
 * Page wrapper at `pages/Finance/Payments/List.tsx` re-exports this so
 * tests can target it without reaching into the page subtree.
 */
export default function PaymentRecordList() {
  const actionRef = useRef<ActionType>();
  const { message: antMessage } = AntApp.useApp();
  const access = useAccess();

  const [createOpen, setCreateOpen] = useState(false);
  const [createForm] = Form.useForm<CreateFormValues>();

  const [importOpen, setImportOpen] = useState(false);
  const [importForm] = Form.useForm<ImportFormValues>();

  const handleCreate = async () => {
    try {
      const vals = await createForm.validateFields();
      const payload: CreatePaymentRecordRequest = {
        period: vals.period,
        direction: vals.direction,
        counterpartyId: vals.counterpartyId,
        counterpartyType: vals.counterpartyType,
        currency: vals.currency,
        amount: vals.amount,
        paymentDate: vals.paymentDate.format('YYYY-MM-DD'),
        sourceRef: vals.sourceRef,
        remark: vals.remark,
      };
      await financePaymentService.create(payload);
      antMessage.success('已创建支付记录');
      setCreateOpen(false);
      createForm.resetFields();
      actionRef.current?.reload();
    } catch {
      /* handled */
    }
  };

  const handleImport = async () => {
    try {
      const vals = await importForm.validateFields();
      const fileObj = vals.file?.[0]?.originFileObj;
      if (!fileObj) {
        antMessage.error('请选择 CSV 文件');
        return;
      }
      const result = await financePaymentService.importCsv(vals.period, fileObj);
      antMessage.success(
        `导入完成: 成功 ${result.successCount} / 跳过 ${result.skippedCount} / 总行 ${result.totalRows}`,
      );
      setImportOpen(false);
      importForm.resetFields();
      actionRef.current?.reload();
    } catch {
      /* handled */
    }
  };

  const columns: ProColumns<BillingPaymentRecord>[] = [
    { title: '期间', dataIndex: 'period', width: 110 },
    {
      title: '方向',
      dataIndex: 'direction',
      width: 90,
      valueType: 'select',
      fieldProps: {
        options: [
          { value: 'RECEIVABLE', label: '应收 (实收)' },
          { value: 'PAYABLE', label: '应付 (实付)' },
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
      title: '对手方类型',
      dataIndex: 'counterpartyType',
      width: 110,
      valueType: 'select',
      fieldProps: {
        options: [
          { value: 'CUSTOMER', label: '客户' },
          { value: 'CARRIER', label: '承运人' },
          { value: 'WAREHOUSE', label: '仓库' },
        ],
      },
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
        ],
      },
    },
    {
      title: '金额',
      dataIndex: 'amount',
      width: 130,
      hideInSearch: true,
      render: (_, r) => (r.amount != null ? r.amount.toFixed(2) : '—'),
    },
    {
      title: '来源',
      dataIndex: 'source',
      width: 100,
      valueType: 'select',
      fieldProps: {
        options: [
          { value: 'MANUAL', label: '手工' },
          { value: 'CSV', label: 'CSV' },
        ],
      },
    },
    {
      title: '原始单号',
      dataIndex: 'sourceRef',
      width: 160,
      hideInSearch: true,
      render: (_, r) => r.sourceRef ?? '—',
    },
    {
      title: '收/付日期',
      dataIndex: 'paymentDate',
      width: 120,
      hideInSearch: true,
      render: (_, r) => <DateTimeColumn value={r.paymentDate} format="date" />,
    },
    {
      title: '备注',
      dataIndex: 'remark',
      width: 200,
      hideInSearch: true,
      ellipsis: true,
    },
    {
      title: '创建时间',
      dataIndex: 'createdAt',
      width: 180,
      hideInSearch: true,
      render: (_, r) => <DateTimeColumn value={r.createdAt} />,
    },
  ];

  return (
    <>
      <ProTable<BillingPaymentRecord>
        headerTitle="实收/实付"
        actionRef={actionRef}
        rowKey="id"
        columns={columns}
        search={{ labelWidth: 'auto' }}
        request={async (params) => {
          const res = await financePaymentService.list({
            period: params.period,
            direction: params.direction,
            page: params.current ?? 1,
            size: params.pageSize ?? 20,
          });
          return { data: res.records, total: res.total, success: true };
        }}
        pagination={{ defaultPageSize: 20, showSizeChanger: true }}
        toolBarRender={() => [
          <Button
            key="create"
            type="primary"
            disabled={!access.canRead('finance:payment:edit')}
            onClick={() => {
              createForm.resetFields();
              createForm.setFieldsValue({
                direction: 'RECEIVABLE',
                counterpartyType: 'CUSTOMER',
                currency: 'CNY',
              });
              setCreateOpen(true);
            }}
          >
            新建
          </Button>,
          <Button
            key="import"
            disabled={!access.canRead('finance:payment:edit')}
            onClick={() => {
              importForm.resetFields();
              setImportOpen(true);
            }}
          >
            CSV 导入
          </Button>,
        ]}
      />

      {/* Create modal — manual entry */}
      <Modal
        title="新建支付记录"
        open={createOpen}
        onOk={handleCreate}
        onCancel={() => {
          setCreateOpen(false);
          createForm.resetFields();
        }}
        okText="创建"
        cancelText="取消"
        destroyOnClose
        width={560}
      >
        <Form<CreateFormValues> form={createForm} layout="vertical">
          <Space size="middle" style={{ display: 'flex' }}>
            <Form.Item
              name="period"
              label="期间 (YYYY-MM)"
              rules={[
                { required: true, message: '请输入期间' },
                { pattern: PERIOD_PATTERN, message: '格式 YYYY-MM (例 2026-10)' },
              ]}
              style={{ flex: 1, minWidth: 200 }}
            >
              <Input placeholder="2026-10" />
            </Form.Item>
            <Form.Item
              name="direction"
              label="方向"
              rules={[{ required: true, message: '请选择方向' }]}
              style={{ flex: 1, minWidth: 200 }}
            >
              <Select
                options={[
                  { value: 'RECEIVABLE', label: '应收 (实收)' },
                  { value: 'PAYABLE', label: '应付 (实付)' },
                ]}
                placeholder="请选择"
              />
            </Form.Item>
          </Space>
          <Space size="middle" style={{ display: 'flex' }}>
            <Form.Item
              name="counterpartyId"
              label="对手方 ID"
              rules={[{ required: true, message: '请输入对手方 ID' }]}
              style={{ flex: 1, minWidth: 200 }}
            >
              <InputNumber min={1} style={{ width: '100%' }} />
            </Form.Item>
            <Form.Item
              name="counterpartyType"
              label="对手方类型"
              rules={[{ required: true, message: '请选择对手方类型' }]}
              style={{ flex: 1, minWidth: 200 }}
            >
              <Select
                options={[
                  { value: 'CUSTOMER', label: '客户' },
                  { value: 'CARRIER', label: '承运人' },
                  { value: 'WAREHOUSE', label: '仓库' },
                ]}
                placeholder="请选择"
              />
            </Form.Item>
          </Space>
          <Space size="middle" style={{ display: 'flex' }}>
            <Form.Item
              name="currency"
              label="币种"
              rules={[{ required: true, message: '请选择币种' }]}
              style={{ flex: 1, minWidth: 200 }}
            >
              <Select
                options={[
                  { value: 'CNY', label: 'CNY' },
                  { value: 'USD', label: 'USD' },
                ]}
                placeholder="请选择"
              />
            </Form.Item>
            <Form.Item
              name="amount"
              label="金额"
              rules={[{ required: true, message: '请输入金额' }]}
              style={{ flex: 1, minWidth: 200 }}
            >
              <InputNumber min={0} step={0.01} style={{ width: '100%' }} />
            </Form.Item>
          </Space>
          <Form.Item
            name="paymentDate"
            label="收/付日期"
            rules={[{ required: true, message: '请选择日期' }]}
          >
            <DatePicker style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="sourceRef" label="原始单号">
            <Input placeholder="可选" />
          </Form.Item>
          <Form.Item name="remark" label="备注">
            <Input.TextArea rows={2} placeholder="可选" />
          </Form.Item>
        </Form>
      </Modal>

      {/* Import modal — CSV upload (period + file). */}
      <Modal
        title="CSV 导入"
        open={importOpen}
        onOk={handleImport}
        onCancel={() => {
          setImportOpen(false);
          importForm.resetFields();
        }}
        okText="开始导入"
        cancelText="取消"
        destroyOnClose
        width={520}
      >
        <Form<ImportFormValues> form={importForm} layout="vertical">
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
            name="file"
            label="CSV 文件"
            valuePropName="fileList"
            getValueFromEvent={(e) => (Array.isArray(e) ? e : e?.fileList)}
            rules={[{ required: true, message: '请选择 CSV 文件' }]}
          >
            <Upload
              beforeUpload={() => false}
              accept=".csv,text/csv"
              maxCount={1}
              data-testid="csv-upload"
            >
              <Button>选择文件</Button>
            </Upload>
          </Form.Item>
        </Form>
      </Modal>
    </>
  );
}