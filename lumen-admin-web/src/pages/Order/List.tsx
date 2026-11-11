/**
 * Lumen Admin — 货运委托单列表 (Iteration 4.6).
 *
 * 筛选项: 委托单号 / 状态 / 业务类型 / 客户 ID。点击行进入详情。
 *
 * <p>筛选项的取值来自 `services/order/orderService.ts` 里那份**受控词表常量** ——
 * 与后端 `OrderConstants` 逐字一致, 与建单表单用的是同一份, 不会两处漂移。
 */

import type { ActionType, ProColumns } from '@ant-design/pro-components';
import { ProTable } from '@ant-design/pro-components';
import { App as AntApp, Button } from 'antd';
import { useRef } from 'react';
import { useNavigate } from 'react-router-dom';

import { DateTimeColumn } from '@/components/columns/DateTimeColumn';
import { OrderStatusTag } from '@/components/order/OrderStatusTag';
import { useAccess } from '@/hooks/useAccess';
import {
  orderService,
  BUSINESS_TYPE_OPTIONS,
  ORDER_STATUS_OPTIONS,
} from '@/services/order/orderService';
import type { OrderRow } from '@/services/order/orderService';

export default function OrderList() {
  const actionRef = useRef<ActionType>();
  const nav = useNavigate();
  const { message: antMessage } = AntApp.useApp();
  const access = useAccess();

  const columns: ProColumns<OrderRow>[] = [
    { title: '委托单号', dataIndex: 'orderNo', width: 180 },
    { title: '客户 ID', dataIndex: 'customerId', width: 110, valueType: 'digit' },
    {
      title: '业务类型',
      dataIndex: 'businessType',
      width: 180,
      valueType: 'select',
      fieldProps: { options: BUSINESS_TYPE_OPTIONS },
    },
    { title: '贸易方向', dataIndex: 'tradeDirection', width: 140, hideInSearch: true },
    { title: '贸易条款', dataIndex: 'incoterms', width: 140, hideInSearch: true },
    { title: '服务类型', dataIndex: 'serviceType', width: 140, hideInSearch: true },
    { title: '起运地', dataIndex: 'originAddress', width: 160, ellipsis: true },
    { title: '目的地', dataIndex: 'destAddress', width: 160, ellipsis: true },
    {
      title: '重量 (kg)',
      dataIndex: 'totalWeightKg',
      width: 100,
      hideInSearch: true,
      render: (_, r) => r.totalWeightKg ?? '—',
    },
    {
      title: '体积 (m³)',
      dataIndex: 'totalVolumeCbm',
      width: 100,
      hideInSearch: true,
      render: (_, r) => r.totalVolumeCbm ?? '—',
    },
    {
      title: '状态',
      dataIndex: 'orderStatus',
      width: 110,
      valueType: 'select',
      fieldProps: { options: ORDER_STATUS_OPTIONS },
      render: (_, r) => <OrderStatusTag value={r.orderStatus} />,
    },
    {
      title: 'ETD',
      dataIndex: 'etd',
      width: 110,
      hideInSearch: true,
    },
    {
      title: 'ETA',
      dataIndex: 'eta',
      width: 110,
      hideInSearch: true,
    },
    {
      title: '创建时间',
      dataIndex: 'createdAt',
      width: 170,
      hideInSearch: true,
      render: (_, r) => <DateTimeColumn value={r.createdAt} />,
    },
    {
      title: '操作',
      valueType: 'option',
      width: 140,
      render: (_, record) => [
        <Button
          key="detail"
          type="link"
          size="small"
          disabled={!access.canRead('order:view')}
          onClick={() => nav(`/orders/${record.id}`)}
        >
          详情
        </Button>,
      ],
    },
  ];

  return (
    <ProTable<OrderRow>
      headerTitle="货运委托单"
      actionRef={actionRef}
      rowKey="id"
      columns={columns}
      search={{ labelWidth: 'auto' }}
      request={async (params) => {
        try {
          const res = await orderService.page({
            current: params.current,
            size: params.pageSize,
            orderNo: params.orderNo as string | undefined,
            // 表格列的 dataIndex 是 orderStatus, 后端 query 参数叫 status —— 这里做一次改名。
            status: params.orderStatus as string | undefined,
            businessType: params.businessType as string | undefined,
            customerId: params.customerId as number | undefined,
          });
          return { data: res.records, total: res.total, success: true };
        } catch {
          antMessage.error('委托单列表加载失败');
          return { data: [], total: 0, success: false };
        }
      }}
      pagination={{ defaultPageSize: 20, showSizeChanger: true }}
      toolBarRender={() => [
        <Button
          key="add"
          type="primary"
          disabled={!access.canRead('order:create')}
          onClick={() => nav('/orders/new')}
        >
          新建委托单
        </Button>,
      ]}
    />
  );
}
