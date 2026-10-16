/**
 * Lumen Admin — CS 客服工单 SLA 视图 (Iteration 4.2 PR6).
 *
 * `/cs/exceptions` — 异常工单 SLA 视角列表。每行除了常规的工单字段
 * (异常 ID / 类型 / 状态 / 处理人), 还嵌入两个 CS-only 视图:
 *   - `EscalationChain` — 升级链 (L1 / L2 / L3) + 升级次数
 *   - `SlaCountdownTag` — 剩余/超期分钟数 + 颜色编码
 *
 * 后端契约见 `EscalationDtos.ExceptionSlaView`; 服务模块
 * `services/cs/exceptionView.ts` 暴露 `csExceptionViewService.list`.
 */

import type { ActionType, ProColumns } from '@ant-design/pro-components';
import { ProTable } from '@ant-design/pro-components';
import { useRef } from 'react';

import { DateTimeColumn } from '@/components/columns/DateTimeColumn';
import { StatusTag } from '@/components/columns/StatusTag';
import EscalationChain from '@/components/cs/EscalationChain';
import SlaCountdownTag from '@/components/cs/SlaCountdownTag';
import {
  csExceptionViewService,
  type ExceptionSlaView,
} from '@/services/cs/exceptionView';

const STATUS_OPTIONS = [
  { value: 'OPEN', label: '待处理' },
  { value: 'INVESTIGATING', label: '调查中' },
  { value: 'RESOLVED', label: '已解决' },
  { value: 'CLOSED', label: '已关闭' },
  { value: 'CANCELLED', label: '已撤销' },
];

const SEVERITY_OPTIONS = [
  { value: 'HIGH', label: '高' },
  { value: 'MEDIUM', label: '中' },
  { value: 'LOW', label: '低' },
];

export default function CsExceptionList() {
  const actionRef = useRef<ActionType>();

  const columns: ProColumns<ExceptionSlaView>[] = [
    { title: '异常 ID', dataIndex: 'exceptionId', width: 100 },
    { title: '类型', dataIndex: 'type', width: 110 },
    {
      title: '状态',
      dataIndex: 'status',
      width: 110,
      valueType: 'select',
      fieldProps: { options: STATUS_OPTIONS },
      render: (_, r) => <StatusTag value={r.status} />,
    },
    {
      title: '严重度',
      dataIndex: 'severity',
      width: 100,
      valueType: 'select',
      fieldProps: { options: SEVERITY_OPTIONS },
    },
    {
      title: '当前 Level',
      dataIndex: 'currentLevel',
      width: 220,
      hideInSearch: true,
      render: (_, r) => (
        <EscalationChain
          currentLevel={r.currentLevel}
          escalationCount={r.slaEvents.filter((e) => e.eventType === 'ESCALATED').length}
        />
      ),
    },
    {
      title: 'SLA 倒计时',
      dataIndex: 'businessMinutesElapsed',
      width: 220,
      hideInSearch: true,
      render: (_, r) => (
        <SlaCountdownTag
          elapsedMinutes={r.businessMinutesElapsed}
          targetMinutes={r.resolveTargetMinutes}
        />
      ),
    },
    {
      title: '处理人',
      dataIndex: 'handler',
      width: 140,
      hideInSearch: true,
      render: (_, r) => r.handler ?? '—',
    },
    {
      title: '最新事件时间',
      dataIndex: 'slaEvents',
      width: 180,
      hideInSearch: true,
      render: (_, r) => {
        const last = r.slaEvents[r.slaEvents.length - 1];
        return <DateTimeColumn value={last?.occurredAt ?? ''} />;
      },
    },
  ];

  return (
    <ProTable<ExceptionSlaView>
      headerTitle="异常工单 SLA 视图"
      actionRef={actionRef}
      rowKey="exceptionId"
      columns={columns}
      search={{ labelWidth: 'auto' }}
      request={async (params) => {
        const res = await csExceptionViewService.list({
          current: params.current,
          size: params.pageSize,
          status: params.status,
          severity: ['HIGH', 'MEDIUM', 'LOW'].includes(params.severity as string)
            ? (params.severity as ExceptionSlaView['severity'])
            : undefined,
        });
        return { data: res.records, total: res.total, success: true };
      }}
      pagination={{ defaultPageSize: 20, showSizeChanger: true }}
    />
  );
}