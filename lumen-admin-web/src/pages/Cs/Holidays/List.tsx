/**
 * Lumen Admin — CS 节假日配置 (Iteration 4.2 PR2).
 *
 * 节假日整体跳过 SlaCalculator 的业务时间计算。tenant_id + date 唯一键
 * 防并发重复添加；service 层 ISOLATION 检查保证跨租户不可见.
 */

import type { ActionType, ProColumns } from '@ant-design/pro-components';
import { ProTable } from '@ant-design/pro-components';
import { Button, Popconfirm, App as AntApp } from 'antd';
import { useRef } from 'react';
import { DateTimeColumn } from '@/components/columns/DateTimeColumn';
import { useAccess } from '@/hooks/useAccess';
import { holidayService, type Holiday } from '@/services/cs/holiday';

export default function CsHolidayList() {
  const actionRef = useRef<ActionType>();
  const { message: antMessage } = AntApp.useApp();
  const access = useAccess();

  const handleDelete = async (id: number) => {
    await holidayService.delete(id);
    antMessage.success('已删除');
    actionRef.current?.reload();
  };

  const columns: ProColumns<Holiday>[] = [
    { title: 'ID', dataIndex: 'id', width: 80 },
    { title: '日期', dataIndex: 'holidayDate', width: 140 },
    { title: '描述', dataIndex: 'description', width: 200 },
    {
      title: '创建时间',
      dataIndex: 'createdAt',
      width: 180,
      hideInSearch: true,
      render: (_, r) => <DateTimeColumn value={r.createdAt ?? ''} />,
    },
    {
      title: '操作',
      valueType: 'option',
      width: 120,
      render: (_, record) => [
        <Popconfirm
          key="delete"
          title="确认删除该节假日?"
          okText="删除"
          cancelText="取消"
          onConfirm={() => handleDelete(record.id)}
        >
          <Button
            type="link"
            size="small"
            danger
            disabled={!access.canRead('cs:holiday:config')}
          >
            删除
          </Button>
        </Popconfirm>,
      ],
    },
  ];

  return (
    <ProTable<Holiday>
      headerTitle="节假日配置"
      actionRef={actionRef}
      rowKey="id"
      columns={columns}
      search={false}
      request={async () => {
        const res = await holidayService.list();
        return { data: res.items, total: res.total, success: true };
      }}
      pagination={false}
    />
  );
}