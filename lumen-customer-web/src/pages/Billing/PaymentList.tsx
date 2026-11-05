import { useEffect, useState } from 'react';
import { Alert, Card, Table } from 'antd';
import { portalBillingService } from '../../services/portalBillingService';
import type { PaymentView } from '../../types/billing';
import { zhCN } from '../../locale/zh-CN';

/** 4.5C-2 — 付款记录列表 (只读, 无导出 —— 商家看付款是为了核对"付过没有")。 */
export default function PaymentList() {
  const [rows, setRows] = useState<PaymentView[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let alive = true;
    portalBillingService
      .listPayments()
      .then((p) => {
        if (alive) setRows(p.records);
      })
      .catch(() => {
        if (alive) setError('付款记录加载失败, 请稍后重试');
      })
      .finally(() => {
        if (alive) setLoading(false);
      });
    return () => {
      alive = false;
    };
  }, []);

  return (
    <Card title={zhCN.billing.paymentTitle}>
      {error && <Alert type="error" message={error} style={{ marginBottom: 16 }} />}
      <Table<PaymentView>
        rowKey="id"
        loading={loading}
        dataSource={rows}
        pagination={false}
        columns={[
          { title: zhCN.billing.period, dataIndex: 'period' },
          { title: zhCN.billing.paymentDate, dataIndex: 'paymentDate' },
          {
            title: zhCN.billing.amount,
            dataIndex: 'amount',
            align: 'right',
            render: (v: number | null, r) => `${v ?? '-'} ${r.currency ?? ''}`.trim(),
          },
          { title: zhCN.billing.paymentRef, dataIndex: 'sourceRef' },
          { title: zhCN.billing.paymentSource, dataIndex: 'source' },
        ]}
      />
    </Card>
  );
}
