/**
 * 4.6 — 门户端货运委托单列表。
 *
 * 与城配单列表 (`pages/Order/OrderList.tsx`) 同一套 ProTable 模式,
 * 但走的是**另一个**后端资源 (`/api/customer/freight-order`)。
 */

import React from 'react';
import type { ProColumns } from '@ant-design/pro-components';
import { ProTable } from '@ant-design/pro-components';
import { Button, Card } from 'antd';
import { useNavigate } from 'react-router-dom';
import { portalFreightOrderService } from '../../services/portalFreightOrderService';
import type { FreightOrderRow } from '../../types/freightOrder';
import FreightOrderStatusTag from '../../components/FreightOrderStatusTag';
import { zhCN } from '../../locale/zh-CN';

const STATUS_OPTIONS = Object.entries(zhCN.freightOrder.status).map(
  ([value, label]) => ({ value, label })
);

const FreightOrderList: React.FC = () => {
  const navigate = useNavigate();

  const columns: ProColumns<FreightOrderRow>[] = [
    {
      title: zhCN.freightOrder.column.orderNo,
      dataIndex: 'orderNo',
      width: 180,
      // 后端门户列表**只支持 status 过滤**, 没有 orderNo 检索 —— 这里关掉搜索框,
      // 免得用户填了没反应。补 orderNo 检索列入 Task 11 文档订正清单。
      hideInSearch: true,
    },
    {
      title: zhCN.freightOrder.column.status,
      dataIndex: 'orderStatus',
      width: 110,
      render: (_, record) => <FreightOrderStatusTag status={record.orderStatus} />,
      valueType: 'select',
      valueEnum: Object.fromEntries(
        STATUS_OPTIONS.map((o) => [o.value, { text: o.label }])
      ),
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
      hideInSearch: true,
      render: (_, r) => r.totalWeightKg ?? '—',
    },
    {
      title: zhCN.freightOrder.column.etd,
      dataIndex: 'etd',
      width: 110,
      hideInSearch: true,
    },
    {
      title: zhCN.freightOrder.column.createdAt,
      dataIndex: 'createdAt',
      width: 170,
      hideInSearch: true,
    },
  ];

  return (
    <Card bordered={false}>
      <ProTable<FreightOrderRow>
        headerTitle={zhCN.freightOrder.title}
        rowKey="id"
        columns={columns}
        search={{ labelWidth: 80, defaultCollapsed: false }}
        toolBarRender={() => [
          <Button
            key="create"
            type="primary"
            onClick={() => navigate('/freight-orders/new')}
          >
            {zhCN.freightOrder.create}
          </Button>,
        ]}
        options={{ reload: () => {}, density: true, setting: false }}
        pagination={{ pageSize: 10, showSizeChanger: true }}
        request={async (params) => {
          const { current, pageSize, orderStatus } = params;
          try {
            const r = await portalFreightOrderService.list({
              status: typeof orderStatus === 'string' ? orderStatus : undefined,
              current: current ?? 1,
              size: pageSize ?? 10,
            });
            return { data: r.records, total: r.total, success: true };
          } catch {
            return { data: [], total: 0, success: false };
          }
        }}
        onRow={(record) => ({
          onClick: () => navigate(`/freight-orders/${record.id}`),
          style: { cursor: 'pointer' },
        })}
      />
    </Card>
  );
};

export default FreightOrderList;
