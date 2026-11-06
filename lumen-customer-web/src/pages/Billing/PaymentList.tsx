import { useEffect, useState } from 'react';
import { Alert, Card, Table } from 'antd';
import { portalBillingService } from '../../services/portalBillingService';
import type { PaymentView } from '../../types/billing';
import { money } from '../../utils/money';
import { zhCN } from '../../locale/zh-CN';
import { useServerPagination } from '../../hooks/useServerPagination';

/** 4.5C-2 — 付款记录列表 (只读, 无导出 —— 商家看付款是为了核对"付过没有")。 */
export default function PaymentList() {
  const [rows, setRows] = useState<PaymentView[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  // 4.5C-2.1 — 服务端分页: 只取当前页, total 喂给分页器。
  // 这一页**没有导出端点** —— 分页器是商家看到全部付款记录的唯一出路。
  const { page, size, pagination } = useServerPagination(total);

  useEffect(() => {
    let alive = true;
    setLoading(true);
    portalBillingService
      .listPayments(undefined, page, size)
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
  }, [page, size]);

  return (
    <Card title={zhCN.billing.paymentTitle}>
      {error && <Alert type="error" message={error} style={{ marginBottom: 16 }} />}
      <Table<PaymentView>
        rowKey="id"
        loading={loading}
        dataSource={rows}
        pagination={pagination}
        columns={[
          { title: zhCN.billing.period, dataIndex: 'period' },
          { title: zhCN.billing.paymentDate, dataIndex: 'paymentDate' },
          {
            title: zhCN.billing.amount,
            dataIndex: 'amount',
            align: 'right',
            render: (v: number | null, r) => money(v, r.currency),
          },
          { title: zhCN.billing.paymentRef, dataIndex: 'sourceRef' },
          { title: zhCN.billing.paymentSource, dataIndex: 'source' },
        ]}
      />
    </Card>
  );
}
