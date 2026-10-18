/**
 * 4.5A.4-T4.3 — TrackList page.
 *
 * Antd ProTable of delivery orders scoped to the active customer. Filtering by
 * doNo + status, server-side pagination. Click a row to drill into TrackDetail.
 */

import React from 'react';
import type { ProColumns } from '@ant-design/pro-components';
import { ProTable } from '@ant-design/pro-components';
import { Card } from 'antd';
import { useNavigate } from 'react-router-dom';
import { portalTrackService } from '../../services/portalTrackService';
import type { PortalTrackListItem } from '../../types/portal';
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

const TrackList: React.FC = () => {
  const navigate = useNavigate();

  const columns: ProColumns<PortalTrackListItem>[] = [
    {
      title: zhCN.track.column.doNo,
      dataIndex: 'doNo',
      key: 'doNo',
      width: 180,
    },
    {
      title: zhCN.track.column.status,
      dataIndex: 'status',
      key: 'status',
      width: 110,
      render: (_, record) => <StatusTag status={record.status} />,
      valueType: 'select',
      valueEnum: Object.fromEntries(STATUS_OPTIONS.map((o) => [o.value, { text: o.label }])),
    },
    {
      title: zhCN.track.column.origin,
      dataIndex: 'originAddress',
      key: 'originAddress',
      ellipsis: true,
    },
    {
      title: zhCN.track.column.destination,
      dataIndex: 'destAddress',
      key: 'destAddress',
      ellipsis: true,
    },
    {
      title: zhCN.track.column.recipient,
      dataIndex: 'recipientName',
      key: 'recipientName',
      width: 140,
    },
    {
      title: zhCN.track.column.phone,
      dataIndex: 'recipientPhone',
      key: 'recipientPhone',
      width: 140,
    },
    {
      title: zhCN.track.column.createdAt,
      dataIndex: 'createdAt',
      key: 'createdAt',
      width: 170,
      valueType: 'dateTime',
      hideInSearch: true,
    },
    {
      title: zhCN.track.column.signedAt,
      dataIndex: 'signedAt',
      key: 'signedAt',
      width: 170,
      valueType: 'dateTime',
      hideInSearch: true,
    },
  ];

  return (
    <Card bordered={false}>
      <ProTable<PortalTrackListItem>
        headerTitle={zhCN.track.title}
        rowKey="doId"
        columns={columns}
        search={{
          labelWidth: 80,
          defaultCollapsed: false,
        }}
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
            const r = await portalTrackService.list({
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
          onClick: () => navigate(`/track/${record.doId}`),
          style: { cursor: 'pointer' },
        })}
      />
    </Card>
  );
};

export default TrackList;
