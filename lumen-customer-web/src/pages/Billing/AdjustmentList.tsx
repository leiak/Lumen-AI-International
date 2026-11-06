import { useEffect, useState } from 'react';
import { Alert, Card, Table, Tag, Typography } from 'antd';
import { portalBillingService } from '../../services/portalBillingService';
import type { AdjustmentView } from '../../types/billing';
import { money } from '../../utils/money';
import { zhCN } from '../../locale/zh-CN';
import { useServerPagination } from '../../hooks/useServerPagination';

/**
 * 4.5C-2 — 我的调账单.
 *
 * L1/L2 驳回理由直接列出来 —— 商家提了异议, 审批驳回时必须让他知道为什么,
 * 否则异议就进了黑洞。
 */
export default function AdjustmentList() {
  const [rows, setRows] = useState<AdjustmentView[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  // 4.5C-2.1 — 服务端分页: 只取当前页, total 喂给分页器。
  // 这一页**没有导出端点** —— 分页器是商家看到全部调账单的唯一出路。
  const { page, size, pagination } = useServerPagination(total);

  useEffect(() => {
    let alive = true;
    setLoading(true);
    portalBillingService
      .listAdjustments(page, size)
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
    <Card title={zhCN.billing.adjustmentTitle}>
      {error && <Alert type="error" message={error} style={{ marginBottom: 16 }} />}
      <Table<AdjustmentView>
        rowKey="id"
        loading={loading}
        dataSource={rows}
        pagination={pagination}
        columns={[
          { title: zhCN.billing.adjustmentNo, dataIndex: 'adjustmentNo' },
          {
            title: zhCN.billing.amount,
            dataIndex: 'amount',
            align: 'right',
            render: (v: number | null, r) => money(v, r.currency),
          },
          {
            title: zhCN.billing.status,
            dataIndex: 'status',
            // 颜色判定仍按原始英文码, 只有显示文字换成中文。
            render: (s: string | null) => (
              <Tag
                color={
                  s === 'REJECTED' ? 'red'
                    : s === 'PENDING_L1' || s === 'PENDING_L2' ? 'orange'
                      : s === 'L2_APPROVED' || s === 'POSTED' ? 'green' : 'blue'
                }
              >
                {zhCN.billing.statusText(s)}
              </Tag>
            ),
          },
          { title: zhCN.billing.reason, dataIndex: 'reason' },
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
