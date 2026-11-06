import { Alert, Button, Card, Table } from 'antd';
import { portalBillingService } from '../../services/portalBillingService';
import type { PaymentView } from '../../types/billing';
import { money } from '../../utils/money';
import { zhCN } from '../../locale/zh-CN';
import { useServerPage } from '../../hooks/useServerPage';

/** 4.5C-2 — 付款记录列表 (只读, 无导出 —— 商家看付款是为了核对"付过没有")。 */
export default function PaymentList() {
  // 4.5C-2.1 — 服务端分页: 请求 + 分页 + 错误 + 重试全交给 hook。
  // 这一页**没有导出端点** —— 分页器是商家看到全部付款记录的唯一出路,
  // 所以失败时也不能把 total 清掉 (清掉了分页器整个不渲染)。
  const { rows, loading, error, pagination, reload } = useServerPage(
    (page, size) => portalBillingService.listPayments(undefined, page, size),
  );

  return (
    <Card title={zhCN.billing.paymentTitle}>
      {error && (
        <Alert
          type="error"
          message={error}
          // 「重试」按钮 —— 失败后商家能原地重试, 不用刷新整个浏览器。
          action={
            // autoInsertSpace={false} —— 否则 antd 会在两个汉字中间插空格
            // (显示成「重 试」), 无障碍名就不再等于文案。
            <Button size="small" autoInsertSpace={false} onClick={reload}>
              {zhCN.billing.retry}
            </Button>
          }
          style={{ marginBottom: 16 }}
        />
      )}
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
