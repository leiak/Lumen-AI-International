import { Alert, Button, Card, Table, Tag, Typography, message } from 'antd';
import { portalBillingService } from '../../services/portalBillingService';
import type { InvoiceView } from '../../types/billing';
import { money } from '../../utils/money';
import { zhCN } from '../../locale/zh-CN';
import { useServerPage } from '../../hooks/useServerPage';

/** 4.5C-2 — 发票列表 + xlsx 导出。 */
export default function InvoiceList() {
  // 4.5C-2.1 — 服务端分页: 请求 + 分页 + 错误 + 重试全交给 hook。
  const { rows, loading, error, pagination, reload, tableLocale } = useServerPage(
    (page, size) => portalBillingService.listInvoices(undefined, page, size),
  );

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
      <Table<InvoiceView>
        rowKey="id"
        loading={loading}
        dataSource={rows}
        pagination={pagination}
        // 失败时别让 antd 的「暂无数据」和上方的红色「加载失败」并排 ——
        // 商家会以为自己的 3 张发票 / 40 条对账单被系统删了 (然后打客服电话)。
        // 成功但真的没数据时, 仍显示正常的「暂无数据」。
        locale={tableLocale}
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
            // 与 netAmount 同走 money() —— 同表两列一个带币种一个不带,
            // 商家会以为税额是另一种口径的数。
            render: (v: number | null, r) => money(v, r.currency),
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
