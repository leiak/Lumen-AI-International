/**
 * Lumen Admin — Finance tax-rate management page (Iteration 4.3.2 PR5).
 *
 * Lists tax rates per currency with effective-from/to dates. Operators
 * can:
 *   - 新增税率 (POST /finance/tax-rates) — fill in a new effective period
 *   - 失效 (POST /finance/tax-rates/{id}/invalidate) — close out a
 *     historical rate
 *
 * Status colors: rows with `effectiveTo == null` (open-ended) are tagged
 * green ("当前有效"); rows with a past `effectiveTo` render grey.
 *
 * Backend contract: `BillingTaxRateController.list/active/create/invalidate`.
 * Service wrapper: `services/finance/financeTaxRateService`.
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
  Popconfirm,
  Tag,
} from 'antd';
import { useRef, useState } from 'react';
import dayjs, { type Dayjs } from 'dayjs';

import { DateTimeColumn } from '@/components/columns/DateTimeColumn';
import { useAccess } from '@/hooks/useAccess';
import { financeTaxRateService } from '@/services/finance/financeTaxRateService';
import type { BillingTaxRate } from '@/types/finance';

interface CreateTaxRateFormValues {
  currency: string;
  taxRate: number;
  effectiveFrom: Dayjs;
  effectiveTo?: Dayjs;
}

const CURRENCY_OPTIONS = [
  { value: 'CNY', label: 'CNY' },
  { value: 'USD', label: 'USD' },
  { value: 'EUR', label: 'EUR' },
  { value: 'JPY', label: 'JPY' },
  { value: 'HKD', label: 'HKD' },
];

/**
 * Format the rate as a percentage for display.
 * 0.13 → "13%"
 */
function formatRatePercent(rate: number): string {
  return `${(rate * 100).toFixed(2)}%`;
}

/**
 * Tax-rate admin table with create modal + invalidate action.
 */
export default function FinanceTaxRateList() {
  const actionRef = useRef<ActionType>();
  const { message: antMessage } = AntApp.useApp();
  const access = useAccess();

  const [createOpen, setCreateOpen] = useState(false);
  const [form] = Form.useForm<CreateTaxRateFormValues>();

  const handleCreate = async () => {
    try {
      const values = await form.validateFields();
      await financeTaxRateService.createTaxRate({
        currency: values.currency,
        taxRate: values.taxRate,
        effectiveFrom: values.effectiveFrom.format('YYYY-MM-DD'),
        effectiveTo: values.effectiveTo?.format('YYYY-MM-DD'),
      });
      antMessage.success('已创建税率');
      setCreateOpen(false);
      form.resetFields();
      actionRef.current?.reload();
    } catch {
      /* handled */
    }
  };

  const handleInvalidate = async (record: BillingTaxRate) => {
    try {
      const today = dayjs().format('YYYY-MM-DD');
      await financeTaxRateService.invalidateTaxRate(record.id, today);
      antMessage.success(`已失效税率 #${record.id}`);
      actionRef.current?.reload();
    } catch {
      /* handled */
    }
  };

  const columns: ProColumns<BillingTaxRate>[] = [
    {
      title: '币种',
      dataIndex: 'currency',
      width: 110,
      valueType: 'select',
      fieldProps: { options: CURRENCY_OPTIONS },
    },
    {
      title: '税率',
      dataIndex: 'taxRate',
      width: 110,
      hideInSearch: true,
      render: (_, r) => formatRatePercent(r.taxRate),
    },
    {
      title: '生效起始',
      dataIndex: 'effectiveFrom',
      width: 130,
      hideInSearch: true,
    },
    {
      title: '生效截止',
      dataIndex: 'effectiveTo',
      width: 130,
      hideInSearch: true,
      render: (_, r) =>
        r.effectiveTo == null ? (
          <Tag color="green">长期有效</Tag>
        ) : (
          r.effectiveTo
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
      width: 180,
      render: (_, record) => (
        <>
          {record.effectiveTo == null && (
            <Popconfirm
              key="invalidate"
              title={`确认将税率 #${record.id} 失效至今天?`}
              okText="失效"
              cancelText="取消"
              onConfirm={() => handleInvalidate(record)}
            >
              <Button
                type="link"
                size="small"
                danger
                disabled={!access.canRead('finance:taxrate:edit')}
              >
                失效
              </Button>
            </Popconfirm>
          )}
        </>
      ),
    },
  ];

  return (
    <>
      <ProTable<BillingTaxRate>
        headerTitle="税率管理"
        actionRef={actionRef}
        rowKey="id"
        columns={columns}
        search={{ labelWidth: 'auto' }}
        request={async (params) => {
          const res = await financeTaxRateService.listTaxRates({
            currency: params.currency,
            page: params.current ?? 1,
            size: params.pageSize ?? 20,
          });
          return { data: res.records, total: res.total, success: true };
        }}
        pagination={{ defaultPageSize: 20, showSizeChanger: true }}
        toolBarRender={() => [
          <Button
            key="add"
            type="primary"
            disabled={!access.canRead('finance:taxrate:edit')}
            onClick={() => {
              form.resetFields();
              form.setFieldsValue({
                currency: 'CNY',
                effectiveFrom: dayjs(),
              });
              setCreateOpen(true);
            }}
          >
            新增税率
          </Button>,
        ]}
      />

      <Modal
        title="新增税率"
        open={createOpen}
        onOk={handleCreate}
        onCancel={() => setCreateOpen(false)}
        okText="创建"
        cancelText="取消"
        destroyOnClose
        width={520}
      >
        <Form<CreateTaxRateFormValues> form={form} layout="vertical">
          <Form.Item
            name="currency"
            label="币种"
            rules={[{ required: true, message: '请选择币种' }]}
          >
            <Input placeholder="例如 CNY" maxLength={8} />
          </Form.Item>
          <Form.Item
            name="taxRate"
            label="税率 (小数, 例如 0.13 表示 13%)"
            rules={[{ required: true, message: '请输入税率' }]}
          >
            <InputNumber
              min={0}
              max={1}
              step={0.01}
              style={{ width: '100%' }}
              placeholder="0.13"
            />
          </Form.Item>
          <Form.Item
            name="effectiveFrom"
            label="生效起始日期"
            rules={[{ required: true, message: '请选择生效起始日期' }]}
          >
            <DatePicker style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="effectiveTo" label="生效截止日期 (留空表示长期有效)">
            <DatePicker style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item label="常用币种">
            {CURRENCY_OPTIONS.map((c) => (
              <Button
                key={c.value}
                size="small"
                style={{ marginRight: 8 }}
                onClick={() => form.setFieldsValue({ currency: c.value })}
              >
                {c.label}
              </Button>
            ))}
          </Form.Item>
        </Form>
      </Modal>
    </>
  );
}