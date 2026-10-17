/**
 * Lumen Admin — Finance exchange-rate management page (Iteration 4.3.2 PR5).
 *
 * Lists USD→CNY exchange rates pulled from the remote provider with
 * historical records; lets operators:
 *   - 刷新 (POST /finance/rates/refresh) — trigger immediate remote sync
 *   - 手动录入 (POST /finance/rates/manual) — fill in a special date or
 *     override a contract rate.
 *
 * Status colors are not applicable (rates are read-only history). The
 * table just shows from/to/rate/rateDate/source/createdAt.
 *
 * Backend contract: `BillingRateController.list/refresh/manual`.
 * Service wrapper: `services/finance/financeRateService`.
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
  Space,
} from 'antd';
import { useRef, useState } from 'react';
import dayjs, { type Dayjs } from 'dayjs';

import { DateTimeColumn } from '@/components/columns/DateTimeColumn';
import { useAccess } from '@/hooks/useAccess';
import {
  financeRateService,
} from '@/services/finance/financeRateService';
import type { BillingExchangeRate, RateQuote } from '@/types/finance';
import { formatRate } from '@/utils/currencyFormatter';

interface ManualRateFormValues {
  from: string;
  to: string;
  rate: number;
  rateDate: Dayjs;
}

const CURRENCY_OPTIONS = [
  { value: 'CNY', label: 'CNY (人民币)' },
  { value: 'USD', label: 'USD (美元)' },
  { value: 'EUR', label: 'EUR (欧元)' },
  { value: 'JPY', label: 'JPY (日元)' },
  { value: 'HKD', label: 'HKD (港币)' },
];

/**
 * Exchange-rate admin table with refresh button + manual-entry modal.
 */
export default function FinanceRateList() {
  const actionRef = useRef<ActionType>();
  const { message: antMessage } = AntApp.useApp();
  const access = useAccess();

  const [manualOpen, setManualOpen] = useState(false);
  const [form] = Form.useForm<ManualRateFormValues>();
  const [lastQuote, setLastQuote] = useState<RateQuote | null>(null);

  const handleRefresh = async () => {
    try {
      const quote = await financeRateService.refreshRate();
      setLastQuote(quote);
      antMessage.success(
        `已刷新: 1 ${quote.source?.split(':')[0] ?? ''} = ${formatRate(quote.rate)} (${quote.rateDate})`,
      );
      actionRef.current?.reload();
    } catch {
      /* axios interceptor surfaces the error */
    }
  };

  const handleManualSave = async () => {
    try {
      const values = await form.validateFields();
      await financeRateService.saveManualRate({
        from: values.from,
        to: values.to,
        rate: values.rate,
        rateDate: values.rateDate.format('YYYY-MM-DD'),
      });
      antMessage.success('已保存手动汇率');
      setManualOpen(false);
      form.resetFields();
      actionRef.current?.reload();
    } catch {
      /* handled */
    }
  };

  const columns: ProColumns<BillingExchangeRate>[] = [
    { title: '源币种', dataIndex: 'fromCurrency', width: 110 },
    { title: '目标币种', dataIndex: 'toCurrency', width: 110 },
    {
      title: '汇率',
      dataIndex: 'rate',
      width: 130,
      hideInSearch: true,
      render: (_, r) => formatRate(r.rate),
    },
    { title: '汇率日期', dataIndex: 'rateDate', width: 130, hideInSearch: true },
    { title: '来源', dataIndex: 'source', width: 180, hideInSearch: true },
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
      <ProTable<BillingExchangeRate>
        headerTitle="汇率管理"
        actionRef={actionRef}
        rowKey="id"
        columns={columns}
        search={{ labelWidth: 'auto' }}
        request={async (params) => {
          const res = await financeRateService.listRates({
            from: params.fromCurrency,
            to: params.toCurrency,
            fromDate: params.fromDate,
            toDate: params.toDate,
            page: params.current ?? 1,
            size: params.pageSize ?? 20,
          });
          return { data: res.records, total: res.total, success: true };
        }}
        pagination={{ defaultPageSize: 20, showSizeChanger: true }}
        toolBarRender={() => [
          <Button
            key="refresh"
            type="primary"
            disabled={!access.canRead('finance:rate:view')}
            onClick={handleRefresh}
          >
            刷新远端
          </Button>,
          <Button
            key="manual"
            disabled={!access.canRead('finance:rate:edit')}
            onClick={() => {
              form.resetFields();
              form.setFieldsValue({ from: 'USD', to: 'CNY', rateDate: dayjs() });
              setManualOpen(true);
            }}
          >
            手动录入
          </Button>,
        ]}
      />

      {lastQuote && (
        <Space style={{ marginTop: 8 }} size="small">
          <span style={{ color: '#999' }}>最新报价:</span>
          <strong>{formatRate(lastQuote.rate)}</strong>
          <span style={{ color: '#999' }}>({lastQuote.rateDate}, {lastQuote.source})</span>
        </Space>
      )}

      <Modal
        title="手动录入汇率"
        open={manualOpen}
        onOk={handleManualSave}
        onCancel={() => setManualOpen(false)}
        okText="保存"
        cancelText="取消"
        destroyOnClose
        width={520}
      >
        <Form<ManualRateFormValues> form={form} layout="vertical">
          <Form.Item name="from" label="源币种" rules={[{ required: true, message: '请选择源币种' }]}>
            <Input placeholder="例如 USD" maxLength={8} />
          </Form.Item>
          <Form.Item name="to" label="目标币种" rules={[{ required: true, message: '请选择目标币种' }]}>
            <Input placeholder="例如 CNY" maxLength={8} />
          </Form.Item>
          <Form.Item
            name="rate"
            label="汇率"
            rules={[{ required: true, message: '请输入汇率' }]}
          >
            <InputNumber min={0} step={0.0001} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item
            name="rateDate"
            label="汇率日期"
            rules={[{ required: true, message: '请选择汇率日期' }]}
          >
            <DatePicker style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item label="可用币种">
            <Space wrap>
              {CURRENCY_OPTIONS.map((c) => (
                <Button
                  key={c.value}
                  size="small"
                  onClick={() => form.setFieldsValue({ from: c.value })}
                >
                  {c.label}
                </Button>
              ))}
            </Space>
          </Form.Item>
        </Form>
      </Modal>
    </>
  );
}