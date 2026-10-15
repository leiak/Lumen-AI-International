/**
 * Lumen Admin — CS SLA 配置列表 (Iteration 4.2 PR2).
 *
 * 配置每个 (severity, level) 的 response/resolve minutes 与 escalate_to_level.
 */

import type { ActionType, ProColumns } from '@ant-design/pro-components';
import { ProTable } from '@ant-design/pro-components';
import { Button, Popconfirm, App as AntApp } from 'antd';
import { useRef } from 'react';
import { DateTimeColumn } from '@/components/columns/DateTimeColumn';
import { useAccess } from '@/hooks/useAccess';
import {
  slaConfigService,
  type SlaConfig,
} from '@/services/cs/slaConfig';

export default function CsSlaConfigList() {
  const actionRef = useRef<ActionType>();
  const { message: antMessage } = AntApp.useApp();
  const access = useAccess();

  const handleDelete = async (id: number) => {
    await slaConfigService.delete(id);
    antMessage.success('已删除');
    actionRef.current?.reload();
  };

  const columns: ProColumns<SlaConfig>[] = [
    { title: 'ID', dataIndex: 'id', width: 80, hideInSearch: true },
    {
      title: '严重度',
      dataIndex: 'severity',
      width: 100,
      valueType: 'select',
      fieldProps: { options: [
        { value: 'HIGH', label: '高' },
        { value: 'MEDIUM', label: '中' },
        { value: 'LOW', label: '低' },
      ]},
    },
    {
      title: 'Level',
      dataIndex: 'level',
      width: 80,
      valueType: 'select',
      fieldProps: { options: [
        { value: 'L1', label: 'L1' },
        { value: 'L2', label: 'L2' },
        { value: 'L3', label: 'L3' },
      ]},
    },
    { title: '响应 (min)', dataIndex: 'responseMinutes', width: 110, hideInSearch: true },
    { title: '解决 (min)', dataIndex: 'resolveMinutes', width: 110, hideInSearch: true },
    { title: '升级到', dataIndex: 'escalateToLevel', width: 100, hideInSearch: true },
    {
      title: '更新时间',
      dataIndex: 'updatedAt',
      width: 180,
      hideInSearch: true,
      render: (_, r) => <DateTimeColumn value={r.updatedAt ?? ''} />,
    },
    {
      title: '操作',
      valueType: 'option',
      width: 120,
      render: (_, record) => [
        <Popconfirm
          key="delete"
          title="确认删除该 SLA 配置?"
          okText="删除"
          cancelText="取消"
          onConfirm={() => handleDelete(record.id)}
        >
          <Button
            type="link"
            size="small"
            danger
            disabled={!access.canRead('cs:sla:config')}
          >
            删除
          </Button>
        </Popconfirm>,
      ],
    },
  ];

  return (
    <ProTable<SlaConfig>
      headerTitle="SLA 配置"
      actionRef={actionRef}
      rowKey="id"
      columns={columns}
      search={{ labelWidth: 'auto' }}
      request={async (params) => {
        const res = await slaConfigService.list({
          severity: params.severity,
          level: params.level,
          page: params.current,
          size: params.pageSize,
        });
        return { data: res.items, total: res.total, success: true };
      }}
      pagination={{ defaultPageSize: 20, showSizeChanger: true }}
    />
  );
}