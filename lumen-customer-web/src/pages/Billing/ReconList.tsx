import { useEffect, useState } from 'react';
import { Alert, Button, Card, Space, Table, Tag, Typography, message } from 'antd';
import { Link } from 'react-router-dom';
import { portalBillingService } from '../../services/portalBillingService';
import type { ReconView } from '../../types/billing';
import { zhCN } from '../../locale/zh-CN';

/**
 * 4.5C-2 — 对账列表.
 *
 * 闭期行不可点进详情提异议 (closed=true 时后端也会拒), 所以直接给行操作禁用态,
 * 而不是让商家点了再吃一个 409。
 */
export default function ReconList() {
  const [rows, setRows] = useState<ReconView[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let alive = true;
    portalBillingService
      .listRecons()
      .then((page) => {
        if (alive) setRows(page.records);
      })
      .catch(() => {
        if (alive) setError('对账单加载失败, 请稍后重试');
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
      await portalBillingService.exportRecons();
      message.success(zhCN.billing.exportStarted);
    } catch {
      message.error(zhCN.billing.exportFailed);
    }
  };

  return (
    <Card
      title={zhCN.billing.reconTitle}
      extra={
        <Space>
          <Button onClick={onExport}>{zhCN.billing.exportExcel}</Button>
        </Space>
      }
    >
      {error && <Alert type="error" message={error} style={{ marginBottom: 16 }} />}
      <Table<ReconView>
        rowKey="id"
        loading={loading}
        dataSource={rows}
        pagination={false}
        columns={[
          { title: zhCN.billing.period, dataIndex: 'period' },
          {
            title: zhCN.billing.expected,
            dataIndex: 'expectedAmount',
            align: 'right',
          },
          {
            title: zhCN.billing.actual,
            dataIndex: 'actualAmount',
            align: 'right',
          },
          {
            title: zhCN.billing.variance,
            dataIndex: 'varianceAmount',
            align: 'right',
            render: (v: number | null) => (
              <Typography.Text type={v === null ? undefined : Number(v) === 0 ? undefined : 'danger'}>
                {v ?? '-'}
              </Typography.Text>
            ),
          },
          {
            title: zhCN.billing.periodClosed,
            dataIndex: 'closed',
            render: (c: boolean) =>
              c ? <Tag color="default">{zhCN.billing.closed}</Tag> : <Tag color="green">{zhCN.billing.open}</Tag>,
          },
          {
            title: zhCN.billing.disputeStatus,
            dataIndex: 'disputeAt',
            render: (at: string | null) =>
              at ? <Tag color="orange">{zhCN.billing.disputed}</Tag> : <Tag>{zhCN.billing.notDisputed}</Tag>,
          },
          {
            title: zhCN.common.detail,
            key: 'op',
            render: (_, row) => (
              <Link to={`/reconciliation/${row.id}`}>{zhCN.billing.viewDetail}</Link>
            ),
          },
        ]}
      />
    </Card>
  );
}
