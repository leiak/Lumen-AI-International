/**
 * Lumen Admin — CS 业务时间配置 (Iteration 4.2 PR2).
 *
 * 每条记录 (weekday, startTime, endTime) — SlaCalculator 计算业务时间时
 * 按 weekday 查表，holiday 整体跳过.
 */

import type { ActionType, ProColumns } from '@ant-design/pro-components';
import { ProTable } from '@ant-design/pro-components';
import { Button, Popconfirm, App as AntApp } from 'antd';
import { useRef } from 'react';
import { useAccess } from '@/hooks/useAccess';
import {
  businessCalendarService,
  type BusinessCalendar,
} from '@/services/cs/businessCalendar';

const WEEKDAY_OPTIONS = [
  { value: 1, label: '周一' },
  { value: 2, label: '周二' },
  { value: 3, label: '周三' },
  { value: 4, label: '周四' },
  { value: 5, label: '周五' },
  { value: 6, label: '周六' },
  { value: 7, label: '周日' },
];

export default function CsCalendarList() {
  const actionRef = useRef<ActionType>();
  const { message: antMessage } = AntApp.useApp();
  const access = useAccess();

  const handleDelete = async (id: number) => {
    await businessCalendarService.delete(id);
    antMessage.success('已删除');
    actionRef.current?.reload();
  };

  const columns: ProColumns<BusinessCalendar>[] = [
    { title: 'ID', dataIndex: 'id', width: 80, hideInSearch: true },
    {
      title: '星期',
      dataIndex: 'weekday',
      width: 100,
      valueType: 'select',
      fieldProps: { options: WEEKDAY_OPTIONS.map((o) => ({ value: o.value, label: o.label })) },
      render: (_, r) => WEEKDAY_OPTIONS.find((o) => o.value === r.weekday)?.label ?? r.weekday,
    },
    { title: '开始时间', dataIndex: 'startTime', width: 120 },
    { title: '结束时间', dataIndex: 'endTime', width: 120 },
    {
      title: '操作',
      valueType: 'option',
      width: 120,
      render: (_, record) => [
        <Popconfirm
          key="delete"
          title="确认删除该业务时间条目?"
          okText="删除"
          cancelText="取消"
          onConfirm={() => handleDelete(record.id)}
        >
          <Button
            type="link"
            size="small"
            danger
            disabled={!access.canRead('cs:calendar:config')}
          >
            删除
          </Button>
        </Popconfirm>,
      ],
    },
  ];

  return (
    <ProTable<BusinessCalendar>
      headerTitle="业务时间配置"
      actionRef={actionRef}
      rowKey="id"
      columns={columns}
      search={false}
      request={async () => {
        const res = await businessCalendarService.list();
        return { data: res.items, total: res.total, success: true };
      }}
      pagination={false}
    />
  );
}