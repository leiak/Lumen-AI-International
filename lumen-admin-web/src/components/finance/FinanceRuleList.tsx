/**
 * Lumen Admin — Finance billing-rule list (Iteration 4.3.1 PR4).
 *
 * Admin page for `BillingRule` records (Iteration 4.3.1 PR3 backend).
 * Columns: 名称 (业务类型) / 方向 / 状态 / 优先级 / 创建时间 / 操作.
 * Actions: 详情 (Drawer), 启用 (DRAFT/DISABLED), 停用 (ACTIVE),
 *          新建规则 (Modal form).
 *
 * Status enum: DRAFT / ACTIVE / DISABLED / INVALID.
 *
 * Backend contract: `BillingRuleController.list/get/create/update/activate/disable`.
 * Service wrapper: `services/finance/financeRuleService`.
 */

import type { ActionType, ProColumns } from '@ant-design/pro-components';
import { ProTable } from '@ant-design/pro-components';
import {
  App as AntApp,
  Button,
  Descriptions,
  Drawer,
  Form,
  Input,
  InputNumber,
  Modal,
  Popconfirm,
  Select,
  Space,
} from 'antd';
import { useRef, useState } from 'react';

import { DateTimeColumn } from '@/components/columns/DateTimeColumn';
import { useAccess } from '@/hooks/useAccess';
import {
  financeRuleService,
  type BillingRule,
  type BillingRuleCreateRequest,
  type RuleStatus,
} from '@/services/finance/financeRuleService';

const STATUS_OPTIONS = [
  { value: 'DRAFT',    label: '草稿' },
  { value: 'ACTIVE',   label: '启用' },
  { value: 'DISABLED', label: '已停用' },
  { value: 'INVALID',  label: '失效' },
];

const STATUS_COLOR: Record<RuleStatus, string> = {
  DRAFT: 'default',
  ACTIVE: 'green',
  DISABLED: 'default',
  INVALID: 'red',
};

const STATUS_LABEL: Record<RuleStatus, string> = {
  DRAFT: '草稿',
  ACTIVE: '启用',
  DISABLED: '已停用',
  INVALID: '失效',
};

const BUSINESS_TYPE_OPTIONS = [
  { value: 'LAST_MILE',     label: '末端配送' },
  { value: 'WAREHOUSE_IN',  label: '入库作业' },
  { value: 'WAREHOUSE_OUT', label: '出库作业' },
];

const DIRECTION_OPTIONS = [
  { value: 'RECEIVABLE', label: '应收' },
  { value: 'PAYABLE',    label: '应付' },
];

const COUNTERPARTY_TYPE_OPTIONS = [
  { value: 'CUSTOMER',  label: '客户' },
  { value: 'CARRIER',   label: '承运人' },
  { value: 'WAREHOUSE', label: '仓库' },
];

interface RuleFormValues {
  businessType: string;
  direction: 'RECEIVABLE' | 'PAYABLE';
  counterpartyType: string;
  currency?: string;
  priority?: number;
  effectiveFrom: string;
  effectiveTo?: string;
  remark?: string;
  expression: string;
  resultField?: string;
}

/**
 * Billing-rule admin table with detail drawer and inline create modal.
 */
export default function FinanceRuleList() {
  const actionRef = useRef<ActionType>();
  const { message: antMessage } = AntApp.useApp();
  const access = useAccess();

  // Drawer (rule detail)
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [current, setCurrent] = useState<BillingRule | null>(null);

  // Create modal
  const [createOpen, setCreateOpen] = useState(false);
  const [form] = Form.useForm<RuleFormValues>();

  const openDetail = async (record: BillingRule) => {
    // Re-fetch to ensure items[] is loaded (list view does not include
    // items for size reasons). On error, fall back to the row data.
    try {
      const fresh = await financeRuleService.get(record.id);
      setCurrent(fresh);
    } catch {
      setCurrent(record);
    }
    setDrawerOpen(true);
  };

  const handleActivate = async (id: number) => {
    try {
      await financeRuleService.activate(id);
      antMessage.success('已启用');
      actionRef.current?.reload();
    } catch {
      /* handled */
    }
  };

  const handleDisable = async (id: number) => {
    try {
      await financeRuleService.disable(id);
      antMessage.success('已停用');
      actionRef.current?.reload();
    } catch {
      /* handled */
    }
  };

  const handleCreate = async () => {
    try {
      const values = await form.validateFields();
      const payload: BillingRuleCreateRequest = {
        businessType: values.businessType,
        direction: values.direction,
        counterpartyType: values.counterpartyType,
        currency: values.currency ?? 'CNY',
        priority: values.priority ?? 100,
        effectiveFrom: values.effectiveFrom,
        effectiveTo: values.effectiveTo,
        remark: values.remark,
        items: [
          {
            sequence: 1,
            itemType: 'EXPRESSION',
            expression: values.expression,
            resultField: values.resultField,
          },
        ],
      };
      await financeRuleService.create(payload);
      antMessage.success('已创建规则');
      setCreateOpen(false);
      form.resetFields();
      actionRef.current?.reload();
    } catch {
      /* handled */
    }
  };

  const columns: ProColumns<BillingRule>[] = [
    {
      title: '业务类型',
      dataIndex: 'businessType',
      width: 130,
      valueType: 'select',
      fieldProps: { options: BUSINESS_TYPE_OPTIONS },
    },
    {
      title: '方向',
      dataIndex: 'direction',
      width: 90,
      valueType: 'select',
      fieldProps: { options: DIRECTION_OPTIONS },
    },
    {
      title: '对手方',
      dataIndex: 'counterpartyType',
      width: 110,
      valueType: 'select',
      fieldProps: { options: COUNTERPARTY_TYPE_OPTIONS },
    },
    { title: '优先级', dataIndex: 'priority', width: 90, hideInSearch: true },
    {
      title: '状态',
      dataIndex: 'status',
      width: 100,
      valueType: 'select',
      fieldProps: { options: STATUS_OPTIONS },
      render: (_, r) => (
        <span style={{ color: STATUS_COLOR[r.status] === 'green' ? '#52c41a' : undefined }}>
          {STATUS_LABEL[r.status] ?? r.status}
        </span>
      ),
    },
    {
      title: '生效起始',
      dataIndex: 'effectiveFrom',
      width: 130,
      hideInSearch: true,
    },
    {
      title: '创建时间',
      dataIndex: 'createdAt',
      width: 180,
      hideInSearch: true,
      render: (_, r) => <DateTimeColumn value={r.createdAt} />,
    },
    {
      title: '操作',
      valueType: 'option',
      width: 280,
      render: (_, record) => (
        <Space size="small">
          <Button
            key="detail"
            type="link"
            size="small"
            disabled={!access.canRead('finance:rule:view')}
            onClick={() => openDetail(record)}
          >
            详情
          </Button>
          {(record.status === 'DRAFT' || record.status === 'DISABLED') && (
            <Button
              key="activate"
              type="link"
              size="small"
              disabled={!access.canRead('finance:rule:edit')}
              onClick={() => handleActivate(record.id)}
            >
              启用
            </Button>
          )}
          {record.status === 'ACTIVE' && (
            <Popconfirm
              key="disable"
              title="确认停用该规则?"
              okText="停用"
              cancelText="取消"
              onConfirm={() => handleDisable(record.id)}
            >
              <Button
                type="link"
                size="small"
                danger
                disabled={!access.canRead('finance:rule:edit')}
              >
                停用
              </Button>
            </Popconfirm>
          )}
        </Space>
      ),
    },
  ];

  return (
    <>
      <ProTable<BillingRule>
        headerTitle="计费规则"
        actionRef={actionRef}
        rowKey="id"
        columns={columns}
        search={{ labelWidth: 'auto' }}
        request={async (params) => {
          const res = await financeRuleService.list({
            businessType: params.businessType,
            direction: params.direction,
            status: params.status,
            pageNum: params.current ?? 1,
            pageSize: params.pageSize ?? 20,
          });
          return { data: res.records, total: res.total, success: true };
        }}
        pagination={{ defaultPageSize: 20, showSizeChanger: true }}
        toolBarRender={() => [
          <Button
            key="add"
            type="primary"
            disabled={!access.canRead('finance:rule:edit')}
            onClick={() => {
              form.resetFields();
              form.setFieldsValue({ priority: 100, currency: 'CNY' });
              setCreateOpen(true);
            }}
          >
            新建规则
          </Button>,
        ]}
      />

      <Drawer
        title={current ? `规则详情 — #${current.id}` : '规则详情'}
        open={drawerOpen}
        onClose={() => setDrawerOpen(false)}
        width={560}
      >
        {current && (
          <Descriptions column={1} bordered size="small">
            <Descriptions.Item label="ID">{current.id}</Descriptions.Item>
            <Descriptions.Item label="业务类型">
              {current.businessType}
            </Descriptions.Item>
            <Descriptions.Item label="方向">
              {current.direction === 'RECEIVABLE' ? '应收' : '应付'}
            </Descriptions.Item>
            <Descriptions.Item label="对手方">
              {current.counterpartyType}
            </Descriptions.Item>
            <Descriptions.Item label="币种">
              {current.currency ?? 'CNY'}
            </Descriptions.Item>
            <Descriptions.Item label="优先级">
              {current.priority ?? '—'}
            </Descriptions.Item>
            <Descriptions.Item label="状态">
              {STATUS_LABEL[current.status] ?? current.status}
            </Descriptions.Item>
            <Descriptions.Item label="生效起始">
              {current.effectiveFrom ?? '—'}
            </Descriptions.Item>
            <Descriptions.Item label="生效截止">
              {current.effectiveTo ?? '—'}
            </Descriptions.Item>
            <Descriptions.Item label="备注">
              {current.remark ?? '—'}
            </Descriptions.Item>
            <Descriptions.Item label="规则项 (items)">
              {current.items && current.items.length > 0 ? (
                <ul style={{ paddingLeft: 16, margin: 0 }}>
                  {current.items.map((it) => (
                    <li key={it.id ?? `${it.sequence}-${it.itemType}`}>
                      <strong>[{it.itemType}]</strong> {it.expression}
                      {it.expressionHash && (
                        <span style={{ color: '#999' }}>
                          {' '}#{it.expressionHash}
                        </span>
                      )}
                    </li>
                  ))}
                </ul>
              ) : (
                '—'
              )}
            </Descriptions.Item>
          </Descriptions>
        )}
      </Drawer>

      <Modal
        title="新建规则"
        open={createOpen}
        onOk={handleCreate}
        onCancel={() => setCreateOpen(false)}
        okText="创建"
        cancelText="取消"
        destroyOnClose
        width={600}
      >
        <Form<RuleFormValues> form={form} layout="vertical">
          <Form.Item
            name="businessType"
            label="业务类型"
            rules={[{ required: true, message: '请选择业务类型' }]}
          >
            <Select options={BUSINESS_TYPE_OPTIONS} placeholder="请选择" />
          </Form.Item>
          <Form.Item
            name="direction"
            label="方向"
            rules={[{ required: true, message: '请选择方向' }]}
          >
            <Select options={DIRECTION_OPTIONS} placeholder="请选择" />
          </Form.Item>
          <Form.Item
            name="counterpartyType"
            label="对手方类型"
            rules={[{ required: true, message: '请选择对手方类型' }]}
          >
            <Select options={COUNTERPARTY_TYPE_OPTIONS} placeholder="请选择" />
          </Form.Item>
          <Form.Item name="priority" label="优先级 (数字越小越优先)">
            <InputNumber min={1} max={10000} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item
            name="effectiveFrom"
            label="生效起始日期"
            rules={[{ required: true, message: '请选择生效起始日期' }]}
          >
            <Input type="date" />
          </Form.Item>
          <Form.Item
            name="expression"
            label="Aviator 表达式"
            rules={[{ required: true, message: '请输入表达式' }]}
          >
            <Input.TextArea
              rows={3}
              placeholder="例如: weight * 0.5 + 5"
            />
          </Form.Item>
          <Form.Item name="resultField" label="结果字段">
            <Input placeholder="例如: amount" />
          </Form.Item>
          <Form.Item name="remark" label="备注">
            <Input.TextArea rows={2} />
          </Form.Item>
        </Form>
      </Modal>
    </>
  );
}