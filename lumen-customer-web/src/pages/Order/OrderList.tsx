/**
 * 4.5B.3-T3.3 — OrderList page.
 *
 * Antd ProTable of the active customer's own orders. Filtering by doNo +
 * status, server-side pagination. Click a row to drill into OrderDetail.
 */

import React from 'react';
import type { ProColumns } from '@ant-design/pro-components';
import { ProTable } from '@ant-design/pro-components';
import { Button, Card } from 'antd';
import { useNavigate } from 'react-router-dom';
import { portalOrderService } from '../../services/portalOrderService';
import type { PortalOrderListItem } from '../../types/order';
import StatusTag from '../../components/StatusTag';
import { zhCN } from '../../locale/zh-CN';

const STATUS_OPTIONS = [
  'CREATED',
  'PENDING_DISPATCH',
  'DISPATCHED',
  'IN_TRANSIT',
  'ARRIVED',
  'DELIVERED',
  'EXCEPTION',
  'CANCELLED',
].map((s) => ({
  value: s,
  label: zhCN.status[s as keyof typeof zhCN.status] ?? s,
}));

const OrderList: React.FC = () => {
  const navigate = useNavigate();

  const columns: ProColumns<PortalOrderListItem>[] = [
    {
      title: zhCN.order.column.doNo,
      dataIndex: 'doNo',
      key: 'doNo',
      width: 180,
    },
    {
      title: zhCN.order.column.status,
      dataIndex: 'status',
      key: 'status',
      width: 110,
      render: (_, record) => <StatusTag status={record.status} />,
      valueType: 'select',
      valueEnum: Object.fromEntries(STATUS_OPTIONS.map((o) => [o.value, { text: o.label }])),
    },
    {
      title: zhCN.order.column.origin,
      dataIndex: 'originAddress',
      key: 'originAddress',
      ellipsis: true,
    },
    {
      title: zhCN.order.column.destination,
      dataIndex: 'destAddress',
      key: 'destAddress',
      ellipsis: true,
    },
    {
      title: zhCN.order.column.pickupWindow,
      key: 'pickupWindow',
      width: 320,
      hideInSearch: true,
      render: (_, record) => {
        const { plannedPickupWindowStart: s, plannedPickupWindowEnd: e } = record;
        if (!s) return '—';
        return e ? `${s.replace('T', ' ').slice(0, 16)} ~ ${e.replace('T', ' ').slice(0, 16)}` : s.replace('T', ' ').slice(0, 16);
      },
    },
    {
      title: zhCN.order.column.createdAt,
      dataIndex: 'createdAt',
      key: 'createdAt',
      width: 170,
      valueType: 'dateTime',
      hideInSearch: true,
    },
    {
      title: zhCN.order.column.signedAt,
      dataIndex: 'signedAt',
      key: 'signedAt',
      width: 170,
      valueType: 'dateTime',
      hideInSearch: true,
    },
  ];

  return (
    <Card bordered={false}>
      <ProTable<PortalOrderListItem>
        headerTitle={zhCN.order.title}
        rowKey="doId"
        columns={columns}
        search={{
          labelWidth: 80,
          defaultCollapsed: false,
        }}
        toolBarRender={() => [
          <Button key="create" type="primary" onClick={() => navigate('/orders/new')}>
            {zhCN.order.create}
          </Button>,
        ]}
        options={{
          reload: () => {},
          density: true,
          setting: false,
        }}
        pagination={{
          pageSize: 10,
          showSizeChanger: true,
        }}
        request={async (params) => {
          const { current, pageSize, doNo, status } = params;
          try {
            const r = await portalOrderService.list({
              doNo: typeof doNo === 'string' ? doNo : undefined,
              status: typeof status === 'string' ? status : undefined,
              page: current ?? 1,
              size: pageSize ?? 10,
            });
            return {
              data: r.records,
              total: r.total,
              success: true,
            };
          } catch {
            return { data: [], total: 0, success: false };
          }
        }}
        onRow={(record) => ({
          onClick: () => navigate(`/orders/${record.doId}`),
          style: { cursor: 'pointer' },
        })}
      />
    </Card>
  );
};

export default OrderList;