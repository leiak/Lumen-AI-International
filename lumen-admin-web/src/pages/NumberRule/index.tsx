/**
 * Lumen Admin — Number Rule page.
 *
 * Lists rules from `/api/v1/number-rules`. Two extra per-row actions:
 *
 *   - "预览": calls `POST /number-rules/{code}/preview` (real counter
 *     increment — same endpoint the upstream services use to actually
 *     generate numbers). Result is shown in a Modal so operators can verify
 *     the format without copy-pasting into another window.
 *   - "重置序号": opens a Modal letting the operator pick the next starting
 *     value (default 0) for the current period, then calls
 *     `POST /number-rules/{code}/reset` which clears both the Redis counter
 *     and the DB fallback row in `sys_number_sequence`.
 *
 * Perm gating:
 *   - preview -> `number-rule:preview`
 *   - reset   -> `number-rule:update` (seed has no `number-rule:reset`; the
 *               controller reuses `:update` — see NumberRuleController.java)
 */

import type { ActionType, ProColumns } from '@ant-design/pro-components';
import { ProTable } from '@ant-design/pro-components';
import { EyeOutlined, ReloadOutlined } from '@ant-design/icons';
import {
  App,
  Button,
  InputNumber,
  Modal,
  Popconfirm,
  Space,
  Spin,
  Typography,
} from 'antd';
import { useRef, useState } from 'react';
import { useAccess } from '@/hooks/useAccess';
import { request } from '@/services/request';
import type { ResetPolicy, SysNumberRule } from '@/types/api';

const { Text } = Typography;

const RESET_POLICY_ENUM: Record<ResetPolicy, { text: string }> = {
  DAY: { text: '按天' },
  MONTH: { text: '按月' },
  YEAR: { text: '按年' },
  NEVER: { text: '从不' },
};

export default function NumberRule() {
  const actionRef = useRef<ActionType>();
  const access = useAccess();
  const { message } = App.useApp();

  const reload = () => actionRef.current?.reload();

  // ---- Preview modal state ----
  const [previewOpen, setPreviewOpen] = useState(false);
  const [previewRule, setPreviewRule] = useState<SysNumberRule | null>(null);
  const [previewResult, setPreviewResult] = useState<string>('');
  const [previewLoading, setPreviewLoading] = useState(false);

  // ---- Reset modal state ----
  const [resetOpen, setResetOpen] = useState(false);
  const [resettingRule, setResettingRule] = useState<SysNumberRule | null>(null);
  const [resetValue, setResetValue] = useState<number>(0);
  const [resetSubmitting, setResetSubmitting] = useState(false);

  const openPreview = async (rule: SysNumberRule) => {
    setPreviewRule(rule);
    setPreviewOpen(true);
    setPreviewResult('');
    setPreviewLoading(true);
    try {
      // Interceptor unwraps R<T>; backend returns the generated string inside data.
      const v = (await request.post(
        `/number-rules/${rule.code}/preview`,
      )) as unknown as string | null;
      setPreviewResult(v ?? '');
    } catch {
      // request interceptor already surfaced the error notification; just
      // close on next tick so the user doesn't stare at a loading spinner.
      setPreviewOpen(false);
    } finally {
      setPreviewLoading(false);
    }
  };

  const openResetModal = (rule: SysNumberRule) => {
    setResettingRule(rule);
    setResetValue(0);
    setResetOpen(true);
  };

  const confirmReset = async () => {
    if (!resettingRule) return;
    setResetSubmitting(true);
    try {
      await request.post(`/number-rules/${resettingRule.code}/reset`, {
        value: resetValue,
      });
      message.success(`已重置 ${resettingRule.code} 的序号`);
      setResetOpen(false);
      reload();
    } catch {
      // request interceptor surfaces the failure
    } finally {
      setResetSubmitting(false);
    }
  };

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
      width: 180,
      render: (_, record) => {
        const canPreview = access.canRead('number-rule:preview');
        const canReset = access.canRead('number-rule:update');
        return [
          <Button
            key="preview"
            type="link"
            size="small"
            icon={<EyeOutlined />}
            disabled={!canPreview}
            onClick={() => openPreview(record)}
          >
            预览
          </Button>,
          <Popconfirm
            key="reset"
            title="确定要重置此规则的序号吗？"
            description="将清空当前周期的 Redis 与 DB 计数器，下次生成从 1 开始。"
            okText="重置"
            cancelText="取消"
            okButtonProps={{ danger: true }}
            disabled={!canReset}
            onConfirm={() => openResetModal(record)}
          >
            <Button
              type="link"
              size="small"
              danger
              icon={<ReloadOutlined />}
              disabled={!canReset}
            >
              重置
            </Button>
          </Popconfirm>,
        ];
      },
    },
  ];

  return (
    <>
      <ProTable<SysNumberRule>
        headerTitle="业务单号规则"
        rowKey="id"
        columns={columns}
        search={false}
        options={{ reload: true, density: true, setting: true }}
        pagination={false}
        actionRef={actionRef}
        request={async () => {
          const res = (await request.get('/number-rules')) as unknown as
            | SysNumberRule[]
            | null;
          return { data: res ?? [], success: true };
        }}
      />

      <Modal
        title={`预览单号 — ${previewRule?.code ?? ''}`}
        open={previewOpen}
        onCancel={() => setPreviewOpen(false)}
        footer={[
          <Button key="close" onClick={() => setPreviewOpen(false)}>
            关闭
          </Button>,
        ]}
        destroyOnClose
      >
        {previewLoading ? (
          <Spin tip="正在生成..." />
        ) : (
          <Space direction="vertical" size="small" style={{ width: '100%' }}>
            <Text type="secondary">规则编码：{previewRule?.code}</Text>
            <Text type="secondary">
              前缀 / 日期格式 / 序号长度：
              {previewRule?.prefix ?? '(空)'} / {previewRule?.dateFormat ?? 'yyyyMMdd'} /{' '}
              {previewRule?.seqLength ?? 4}
            </Text>
            <div>
              <Text strong>预览结果：</Text>
              <div
                style={{
                  fontSize: 18,
                  fontFamily: 'monospace',
                  padding: '8px 12px',
                  background: '#f5f5f5',
                  border: '1px solid #d9d9d9',
                  borderRadius: 4,
                  wordBreak: 'break-all',
                  marginTop: 4,
                }}
              >
                {previewResult || '(空)'}
              </div>
              <Text type="secondary" style={{ fontSize: 12 }}>
                该结果已写入真实计数器，再次预览会得到递增后的下一个号。
              </Text>
            </div>
          </Space>
        )}
      </Modal>

      <Modal
        title={`重置序号 — ${resettingRule?.code ?? ''}`}
        open={resetOpen}
        onCancel={() => setResetOpen(false)}
        onOk={confirmReset}
        confirmLoading={resetSubmitting}
        okText="确认重置"
        cancelText="取消"
        okButtonProps={{ danger: true }}
        destroyOnClose
      >
        <Space direction="vertical" size="small" style={{ width: '100%' }}>
          <Text>
            将规则 <Text strong>{resettingRule?.code}</Text> 的序号重置为：
          </Text>
          <InputNumber
            min={0}
            value={resetValue}
            onChange={(v) => setResetValue(typeof v === 'number' ? v : 0)}
            style={{ width: '100%' }}
          />
          <Text type="secondary" style={{ fontSize: 12 }}>
            同步更新 sys_number_rule.current_value（仅展示）；下次预览将从 1 开始计数。
          </Text>
        </Space>
      </Modal>
    </>
  );
}
