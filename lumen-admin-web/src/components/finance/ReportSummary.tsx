/**
 * Lumen Admin — Finance report export (Iteration 4.4 PR3).
 *
 * Tabbed workbench for the three period-end Excel exports:
 *
 *   汇总 (Summary)  — period invoice / accrual / adjustment grand totals
 *   明细 (Detail)   — period L2_APPROVED invoice line items
 *   差异 (Variance) — period reconciliation rows
 *
 * Each tab exposes a period + currency form with a 下载 (Download)
 * button. The download is triggered by calling the service (which
 * returns a Blob) and wrapping it in a hidden `<a download>` element.
 * Perm: `finance:report:view`.
 *
 * Backend contract: `BillingReportController.summary/detail/variance`.
 * Service wrapper: `services/finance/financeReportService` (returns Blob).
 */

import { App as AntApp, Button, Form, Input, Select, Space, Tabs } from 'antd';
import { useState } from 'react';

import { useAccess } from '@/hooks/useAccess';
import { financeReportService } from '@/services/finance/financeReportService';

interface ReportFormValues {
  period: string;
  currency: string;
}

const PERIOD_PATTERN = /^\d{4}-(0[1-9]|1[0-2])$/;

const CURRENCY_OPTIONS = [
  { value: 'CNY', label: 'CNY' },
  { value: 'USD', label: 'USD' },
];

const TAB_ITEMS = [
  { key: 'summary',  label: '汇总' },
  { key: 'detail',   label: '明细' },
  { key: 'variance', label: '差异' },
];

type ReportEndpoint = 'summary' | 'detail' | 'variance';

const FILE_LABEL: Record<ReportEndpoint, string> = {
  summary: 'finance-summary',
  detail: 'finance-detail',
  variance: 'finance-variance',
};

/**
 * Report-export workbench — 3 tabs, each with its own period + currency
 * form + 下载 button. Page wrapper at
 * `pages/Finance/Reports/Summary.tsx` re-exports this so tests can
 * target it without reaching into the page subtree.
 */
export default function ReportSummary() {
  const { message: antMessage } = AntApp.useApp();
  const access = useAccess();

  const [activeTab, setActiveTab] = useState<string>('summary');
  const [summaryForm] = Form.useForm<ReportFormValues>();
  const [detailForm] = Form.useForm<ReportFormValues>();
  const [varianceForm] = Form.useForm<ReportFormValues>();
  const [busy, setBusy] = useState<Record<ReportEndpoint, boolean>>({
    summary: false,
    detail: false,
    variance: false,
  });

  const downloadBlob = (endpoint: ReportEndpoint, vals: ReportFormValues, blob: Blob) => {
    const objectUrl = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = objectUrl;
    a.download = `${FILE_LABEL[endpoint]}-${vals.period}-${vals.currency}.xlsx`;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(objectUrl);
    antMessage.success('已开始下载');
  };

  const handleDownload = async (
    endpoint: ReportEndpoint,
    form: typeof summaryForm,
  ) => {
    try {
      const vals = await form.validateFields();
      setBusy((s) => ({ ...s, [endpoint]: true }));
      const blob = await financeReportService[endpoint]({
        period: vals.period,
        currency: vals.currency,
      });
      downloadBlob(endpoint, vals, blob);
    } catch {
      /* handled by axios interceptor */
    } finally {
      setBusy((s) => ({ ...s, [endpoint]: false }));
    }
  };

  const renderForm = (
    endpoint: ReportEndpoint,
    form: typeof summaryForm,
  ) => (
    <Form<ReportFormValues>
      form={form}
      layout="inline"
      initialValues={{ currency: 'CNY' }}
      style={{ marginTop: 16 }}
    >
      <Form.Item
        name="period"
        label="期间 (YYYY-MM)"
        rules={[
          { required: true, message: '请输入期间' },
          { pattern: PERIOD_PATTERN, message: '格式 YYYY-MM (例 2026-10)' },
        ]}
      >
        <Input placeholder="2026-10" style={{ width: 180 }} />
      </Form.Item>
      <Form.Item
        name="currency"
        label="币种"
        rules={[{ required: true, message: '请选择币种' }]}
      >
        <Select options={CURRENCY_OPTIONS} placeholder="请选择" style={{ width: 140 }} />
      </Form.Item>
      <Form.Item>
        <Space>
          <Button
            type="primary"
            loading={busy[endpoint]}
            disabled={!access.canRead('finance:report:view')}
            onClick={() => handleDownload(endpoint, form)}
          >
            下载 Excel
          </Button>
        </Space>
      </Form.Item>
    </Form>
  );

  return (
    <div>
      <Tabs
        activeKey={activeTab}
        onChange={setActiveTab}
        items={TAB_ITEMS.map((t) => {
          const form =
            t.key === 'summary'
              ? summaryForm
              : t.key === 'detail'
                ? detailForm
                : varianceForm;
          const endpoint = t.key as ReportEndpoint;
          return {
            key: t.key,
            label: t.label,
            children: renderForm(endpoint, form),
          };
        })}
      />
    </div>
  );
}