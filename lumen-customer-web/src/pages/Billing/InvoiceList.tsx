import { useEffect, useState } from 'react';
import { Alert, Button, Card, Table, Tag, message } from 'antd';
import { portalBillingService } from '../../services/portalBillingService';
import type { InvoiceView } from '../../types/billing';
import { zhCN } from '../../locale/zh-CN';

/** 4.5C-2 — 发票列表 + xlsx 导出。 */
export default function InvoiceList() {
  const [rows, setRows] = useState<InvoiceView[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let alive = true;
    portalBillingService
      .listInvoices()
      .then((p) => {
        if (alive) setRows(p.records);
      })
      .catch(() => {
        if (alive) setError('发票加载失败, 请稍后重试');
      })
      .finally(() => {
        if (alive) setLoading(false);
      });
    return () => {
      alive = false;
    };
  }, []);

  const onExport = async () => {
    try {
      await portalBillingService.exportInvoices();
      message.success(zhCN.billing.exportStarted);
    } catch {
      message.error(zhCN.billing.exportFailed);
    }
  };

  return (
    <Card
      title={zhCN.billing.invoiceTitle}
      extra={<Button onClick={onExport}>{zhCN.billing.exportExcel}</Button>}
    >
      {error && <Alert type="error" message={error} style={{ marginBottom: 16 }} />}
      <Table<InvoiceView>
        rowKey="id"
        loading={loading}
        dataSource={rows}
        pagination={false}
        columns={[
          { title: zhCN.billing.invoiceNo, dataIndex: 'invoiceNo' },
          { title: zhCN.billing.period, dataIndex: 'period' },
          {
            title: zhCN.billing.netAmount,
            dataIndex: 'netAmount',
            align: 'right',
            render: (v: number | null, r) => `${v ?? '-'} ${r.currency ?? ''}`.trim(),
          },
          {
            title: zhCN.billing.taxAmount,
            dataIndex: 'taxAmount',
            align: 'right',
          },
          {
            title: zhCN.billing.status,
            dataIndex: 'status',
            render: (s: string | null) => <Tag color={s === 'PUSHED' ? 'green' : 'blue'}>{s ?? '-'}</Tag>,
          },
        ]}
      />
    </Card>
  );
}
