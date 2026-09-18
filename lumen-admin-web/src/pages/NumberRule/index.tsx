/**
 * Lumen Admin — Number Rule page.
 *
 * Lists rules from `/api/v1/number-rules` and previews next-number generation
 * via `POST /number-rules/{code}/preview` (see NumberRuleController.java).
 *
 * Preview increments the rule's persisted sequence — running it twice in
 * quick succession will produce consecutive numbers. Operators use the
 * preview button to confirm the format before letting upstream services
 * request real numbers.
 */

import type { ProColumns } from '@ant-design/pro-components';
import { ProTable } from '@ant-design/pro-components';
import { App, Button } from 'antd';
import { request } from '@/services/request';
import type { ResetPolicy, SysNumberRule } from '@/types/api';

const RESET_POLICY_ENUM: Record<ResetPolicy, { text: string }> = {
  DAY: { text: '按天' },
  MONTH: { text: '按月' },
  YEAR: { text: '按年' },
  NEVER: { text: '从不' },
};

export default function NumberRule() {
  const { message } = App.useApp();

  const columns: ProColumns<SysNumberRule>[] = [
    { title: '编码', dataIndex: 'code', width: 200 },
    { title: '前缀', dataIndex: 'prefix', width: 120 },
    { title: '日期格式', dataIndex: 'dateFormat', width: 140 },
    { title: '序号长度', dataIndex: 'seqLength', width: 100, hideInSearch: true },
    {
      title: '重置策略',
      dataIndex: 'resetPolicy',
      width: 120,
      valueEnum: RESET_POLICY_ENUM,
      valueType: 'select',
    },
    { title: '当前值', dataIndex: 'currentValue', width: 100, hideInSearch: true },
    { title: '描述', dataIndex: 'description', ellipsis: true },
    {
      title: '操作',
      valueType: 'option',
      width: 140,
      render: (_, record) => [
        <Button
          key="preview"
          type="link"
          size="small"
          onClick={async () => {
            try {
              // Interceptor already unwraps R<T>; the cast gives the raw string
              // the backend returned inside R.ok(...).
              const v = (await request.post(
                `/number-rules/${record.code}/preview`,
              )) as unknown as string | null;
              message.success(`生成：${v ?? '(空)'}`);
            } catch {
              // request interceptor already surfaced the error notification.
            }
          }}
        >
          预览
        </Button>,
      ],
    },
  ];

  return (
    <ProTable<SysNumberRule>
      headerTitle="业务单号规则"
      rowKey="id"
      columns={columns}
      search={false}
      options={{ reload: true, density: true, setting: true }}
      pagination={false}
      request={async () => {
        const res = (await request.get('/number-rules')) as unknown as
          | SysNumberRule[]
          | null;
        return { data: res ?? [], success: true };
      }}
    />
  );
}
