/**
 * 4.6 — 门户端货运委托单列表。
 *
 * <p><b>走 `useServerPage` + antd `Table`</b> —— 与商家门户另外 4 个列表页
 * (`pages/Billing/` 下的 ReconList / InvoiceList / PaymentList / AdjustmentList)
 * 同一套写法。走的是**另一个**后端资源 (`/api/customer/freight-order`)。
 *
 * <p><b>为什么不再用 ProTable</b>: ProTable 的 `request` 回调只能返回
 * `{data, total, success}` —— 失败时除了 `success: false` 没有任何地方能挂提示,
 * 于是失败被**静默吞掉**: 商家看到的是 antd 默认的空态「暂无数据」, 以为系统里
 * 没有自己的单。`useServerPage` 把请求 / 分页 / 错误 / 重试收在一处
 * (见 `hooks/useServerPage.ts` 的头注释): 失败时页面顶部挂红色「加载失败」+
 * 「重试」按钮, 表格的空态显示「加载失败, 请点击上方重试」而不是「暂无数据」。
 *
 * <p>状态筛选是自己实现的 —— 后端门户列表**只支持 status 过滤**
 * (`PortalFreightOrderController.list` 的入参只有 current / size / status),
 * 没有 orderNo 检索, 所以这里只给一个状态下拉 + 一个「查询」按钮。
 */

import React, { useState } from 'react';
import { Alert, Button, Card, Form, Select, Table } from 'antd';
import { useNavigate } from 'react-router-dom';
import { portalFreightOrderService } from '../../services/portalFreightOrderService';
import type { FreightOrderRow } from '../../types/freightOrder';
import FreightOrderStatusTag from '../../components/FreightOrderStatusTag';
import { zhCN } from '../../locale/zh-CN';
import { useServerPage } from '../../hooks/useServerPage';

/**
 * 状态筛选项 —— 取自 `zhCN.freightOrder.status` (委托单 9 个状态)。
 */
const STATUS_OPTIONS = Object.entries(zhCN.freightOrder.status).map(
  ([value, label]) => ({ value, label })
);

const FreightOrderList: React.FC = () => {
  const navigate = useNavigate();
  /**
   * **已经应用的**筛选条件 —— 只有它变了才会重新请求。
   *
   * <p>下拉框里选中但还没点「查询」的值不放在这里 —— 否则每改一次下拉就发一次
   * 请求, 与 ProTable 时代「点查询才提交」的交互不一致。
   */
  const [status, setStatus] = useState<string | undefined>(undefined);

  const { rows, loading, error, pagination, reload, tableLocale } = useServerPage<FreightOrderRow>(
    (page, size) =>
      portalFreightOrderService
        .list({ status, current: page, size })
        /**
         * 后端返的是 `PageResult` (records / total / pageNum / pageSize),
         * hook 要的是 `MerchantPage` (records / total / page / size) —— 这里换名。
         */
        .then((r) => ({
          records: r.records,
          total: r.total,
          page: r.pageNum,
          size: r.pageSize,
        })),
    /**
     * 筛选条件必须进 `deps` —— hook 的 fetcher 存在 ref 里, 变了不会自动重取。
     *
     * <p>`deps` 的**长度**在组件生命周期内必须恒定 —— 这里恒为 1。
     */
    [status],
  );

  const onSearch = (values: { status?: string }) => {
    setStatus(values.status);
    /**
     * 筛选变了 → 回到第 1 页。
     *
     * <p>**`useServerPage` 的 deps 变化不会重置页码** —— 它的 effect 依赖里
     * 只有 `page / size / reloadTick / deps`, deps 变了只是**用当前的页码**重新
     * 请求。不显式处理的话, 商家在第 3 页改筛选 → 请求发的是「第 3 页 + 新筛选」,
     * 结果多半是一片空白, 看起来像筛选把单子筛没了。
     */
    pagination.onChange?.(1, pagination.pageSize ?? 20);
  };

  return (
    <Card
      bordered={false}
      title={zhCN.freightOrder.title}
      extra={
        <Button type="primary" onClick={() => navigate('/freight-orders/new')}>
          {zhCN.freightOrder.create}
        </Button>
      }
    >
      <Form layout="inline" onFinish={onSearch} style={{ marginBottom: 16 }}>
        <Form.Item label={zhCN.freightOrder.column.status} name="status">
          <Select
            allowClear
            placeholder="全部"
            style={{ width: 160 }}
            options={STATUS_OPTIONS}
          />
        </Form.Item>
        <Form.Item>
          {/* 「查询」两个字没有对应的 locale 键 (`zhCN.common` 里只有
              confirm / cancel / save / submit / back / refresh / loading /
              yes / no / detail) —— ProTable 时代这个按钮是 antd 内置的,
              自己写就补一个字面量。 */}
          <Button type="primary" htmlType="submit">查询</Button>
        </Form.Item>
      </Form>

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

      <Table<FreightOrderRow>
        rowKey="id"
        loading={loading}
        dataSource={rows}
        /**
         * 每页 20 条 —— 与后端 `PortalFreightOrderController.list` 的 size 默认值
         * 一致 (两个数字不一样时, 后端的默认值永远不生效, 容易在后续改动里踩坑)。
         * 分页器本身由 hook 提供 (`pagination` 里的 current / pageSize / total /
         * onChange 都齐了), 这里不再自己写一份。
         */
        pagination={pagination}
        /**
         * 失败时别让 antd 的「暂无数据」和上方的红色「加载失败」并排 ——
         * 商家会以为自己的委托单被系统删了。成功但真的没数据时, 仍显示正常的
         * 「暂无数据」。
         */
        locale={tableLocale}
        columns={[
          {
            title: zhCN.freightOrder.column.orderNo,
            dataIndex: 'orderNo',
            width: 180,
          },
          {
            title: zhCN.freightOrder.column.status,
            dataIndex: 'orderStatus',
            width: 110,
            render: (_, record) => <FreightOrderStatusTag status={record.orderStatus} />,
          },
          {
            title: zhCN.freightOrder.column.businessType,
            dataIndex: 'businessType',
            width: 110,
          },
          {
            title: zhCN.freightOrder.column.origin,
            dataIndex: 'originAddress',
            ellipsis: true,
          },
          {
            title: zhCN.freightOrder.column.destination,
            dataIndex: 'destAddress',
            ellipsis: true,
          },
          {
            title: zhCN.freightOrder.column.weight,
            dataIndex: 'totalWeightKg',
            width: 110,
            render: (_, r) => r.totalWeightKg ?? '—',
          },
          {
            title: zhCN.freightOrder.column.etd,
            dataIndex: 'etd',
            width: 110,
          },
          {
            title: zhCN.freightOrder.column.createdAt,
            dataIndex: 'createdAt',
            width: 170,
          },
        ]}
        onRow={(record) => ({
          onClick: () => navigate(`/freight-orders/${record.id}`),
          style: { cursor: 'pointer' },
        })}
      />
    </Card>
  );
};

export default FreightOrderList;
