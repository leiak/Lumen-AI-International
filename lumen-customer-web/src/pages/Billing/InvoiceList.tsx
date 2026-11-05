import { useEffect, useState } from 'react';
import { Alert, Button, Card, Table, Tag, Typography, message } from 'antd';
import { portalBillingService } from '../../services/portalBillingService';
import type { InvoiceView } from '../../types/billing';
import { money } from '../../utils/money';
import { zhCN } from '../../locale/zh-CN';

/** 4.5C-2 — 发票列表 + xlsx 导出。 */
export default function InvoiceList() {
  const [rows, setRows] = useState<InvoiceView[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let alive = true;
    portalBillingService
      .listInvoices()
      .then((p) => {
        if (alive) {
          setRows(p.records);
          setTotal(p.total);
        }
      })
      .catch(() => {
        if (alive) setError(zhCN.billing.loadFailed);
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
      {rows.length < total && (
        <Alert
          type="info"
          showIcon
          message={zhCN.billing.truncatedWithExport(total, rows.length)}
          style={{ marginBottom: 16 }}
        />
      )}
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
            render: (v: number | null, r) => money(v, r.currency),
          },
          {
            title: zhCN.billing.taxAmount,
            dataIndex: 'taxAmount',
            align: 'right',
            render: (v: number | null) => v ?? '-',
          },
          {
            title: zhCN.billing.status,
            dataIndex: 'status',
            // 颜色判定仍按原始英文码, 只有显示文字换成中文。
            render: (s: string | null) => (
              <Tag color={s === 'REJECTED' ? 'red' : s === 'PUSHED' ? 'green' : 'blue'}>
                {zhCN.billing.statusText(s)}
              </Tag>
            ),
          },
          {
            title: zhCN.billing.rejectComment,
            key: 'comment',
            render: (_, r) =>
              r.l2Comment || r.l1Comment || (
                <Typography.Text type="secondary">{zhCN.billing.noComment}</Typography.Text>
              ),
          },
        ]}
      />
    </Card>
  );
}
