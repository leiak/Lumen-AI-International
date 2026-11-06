import { Alert, Button, Card, Space, Table, Tag, Typography, message } from 'antd';
import { Link } from 'react-router-dom';
import { portalBillingService } from '../../services/portalBillingService';
import type { ReconView } from '../../types/billing';
import { money } from '../../utils/money';
import { zhCN } from '../../locale/zh-CN';
import { useServerPage } from '../../hooks/useServerPage';

/**
 * 4.5C-2 — 对账列表.
 *
 * 本页只做只读展示。闭期 / 已异议的判定不在这里做 —— 列表行始终可点进详情,
 * 由 ReconDetail 决定要不要渲染异议表单 (未闭期与已异议后端也会拒, 409)。
 *
 * 期间状态列: 已闭期 → 灰色「已闭期」; 未闭期 → 橙色「数据可能变动」。
 */
export default function ReconList() {
  // 4.5C-2.1 — 服务端分页: 请求 + 分页 + 错误 + 重试全交给 hook。
  // 页面只管渲染 —— 少了页面里那份复制粘贴的 useEffect, 也就少了
  // 「deps 漏写 page, size → 分页器点得动但数据不变」的静默失效。
  const { rows, loading, error, pagination, reload, tableLocale } = useServerPage(
    (page, size) => portalBillingService.listRecons(undefined, page, size),
  );

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
      <Table<ReconView>
        rowKey="id"
        loading={loading}
        dataSource={rows}
        pagination={pagination}
        // 失败时别让 antd 的「暂无数据」和上方的红色「加载失败」并排 ——
        // 商家会以为自己的 3 张发票 / 40 条对账单被系统删了 (然后打客服电话)。
        // 成功但真的没数据时, 仍显示正常的「暂无数据」。
        // 加载中 (含重试在途) 显示「加载中...」—— 见 hook 里 tableLocale 的注释。
        locale={tableLocale}
        columns={[
          { title: zhCN.billing.period, dataIndex: 'period' },
          {
            // 币种单独一列 —— recon 行的粒度是 (期间, 方向, 往来单位, 币种),
            // 同商家同期间开 CNY 和 USD 就是两行, 不显示币种商家分不清哪行是哪行。
            title: zhCN.billing.currency,
            dataIndex: 'currency',
            render: (c: string | null) => c ?? '-',
          },
          {
            title: zhCN.billing.expected,
            dataIndex: 'expectedAmount',
            align: 'right',
            render: (v: number | null, r) => money(v, r.currency),
          },
          {
            title: zhCN.billing.actual,
            dataIndex: 'actualAmount',
            align: 'right',
            render: (v: number | null, r) => money(v, r.currency),
          },
          {
            title: zhCN.billing.variance,
            dataIndex: 'varianceAmount',
            align: 'right',
            render: (v: number | null, r) => (
              <Typography.Text type={v === null || Number(v) === 0 ? undefined : 'danger'}>
                {money(v, r.currency)}
              </Typography.Text>
            ),
          },
          {
            title: zhCN.billing.periodClosed,
            dataIndex: 'closed',
            // 未闭期是 **warning 色** —— 这一期的数字还在变, 提异议没有意义。
            // 早先用绿色的「开放」, 商家会把它当成好消息, 语义正好相反。
            render: (c: boolean) =>
              c ? <Tag color="default">{zhCN.billing.closed}</Tag> : <Tag color="warning">{zhCN.billing.dataMayChange}</Tag>,
          },
          {
            // 对账状态 —— 后端 ReconView 一直在返回这个字段, 但列表页没渲染,
            // 商家看不到自己那笔差异是「已建调账单 / 已结清 / 已豁免」。
            // 这直接关系到异议闸门: 状态不是 ADJUSTMENT_CREATED 的行商家提不了
            // 异议 (后端闸门), 让他看得见原因比让他撞一次 409 强。
            title: zhCN.billing.reconStatus,
            dataIndex: 'reconciliationStatus',
            render: (s: string | null) => zhCN.billing.statusText(s),
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
