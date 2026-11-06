import { Alert, Button, Card, Table, Tag, Typography } from 'antd';
import { portalBillingService } from '../../services/portalBillingService';
import type { AdjustmentView } from '../../types/billing';
import { money } from '../../utils/money';
import { zhCN } from '../../locale/zh-CN';
import { useServerPage } from '../../hooks/useServerPage';

/**
 * 4.5C-2 — 我的调账单.
 *
 * L1/L2 驳回理由直接列出来 —— 商家提了异议, 审批驳回时必须让他知道为什么,
 * 否则异议就进了黑洞。
 */
export default function AdjustmentList() {
  // 4.5C-2.1 — 服务端分页: 请求 + 分页 + 错误 + 重试全交给 hook。
  // 这一页**没有导出端点** —— 分页器是商家看到全部调账单的唯一出路,
  // 所以失败时也不能把 total 清掉 (清掉了分页器整个不渲染)。
  const { rows, loading, error, pagination, reload } = useServerPage(
    (page, size) => portalBillingService.listAdjustments(page, size),
  );

  // 空态文案跟 loading 联动 —— 三个状态三句话:
  //   - 加载中 (首次加载 / 重试在途) → "加载中…"
  //   - 加载失败 → "加载失败, 请点击上方重试"
  //   - 加载成功但真的没数据 → antd 默认的 "暂无数据"
  const tableLocale = error && !loading
    ? { emptyText: zhCN.billing.loadFailedEmpty }
    : loading
      ? { emptyText: zhCN.billing.loadingEmpty }
      : undefined;

  return (
    <Card title={zhCN.billing.adjustmentTitle}>
      {error && (
        <Alert
          type="error"
          message={error}
          // 「重试」按钮 —— 失败后商家能原地重试, 不用刷新整个浏览器。
          // loading 时禁用 —— 否则商家能连点, reloadTick 连续自增, 后端慢的
          // 时候会同时挂出一堆请求 (旧的会被 alive=false 丢弃、结果不串, 但
          // 请求都发出去了)。
          action={
            <Button size="small" disabled={loading} onClick={reload}>
              {zhCN.billing.retry}
            </Button>
          }
          style={{ marginBottom: 16 }}
        />
      )}
      <Table<AdjustmentView>
        rowKey="id"
        loading={loading}
        dataSource={rows}
        pagination={pagination}
        // 失败时别让 antd 的「暂无数据」和上方的红色「加载失败」并排 ——
        // 商家会以为自己的 3 张发票 / 40 条对账单被系统删了 (然后打客服电话)。
        // 成功但真的没数据时, 仍显示正常的「暂无数据」。
        locale={tableLocale}
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
