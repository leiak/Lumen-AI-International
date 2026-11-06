/**
 * Lumen Admin — Finance adjustment list (Iteration 4.3.2 PR5).
 *
 * Credit / debit note workbench with full 2-step approval flow:
 *
 *   DRAFT        — submit → PENDING_L1
 *   PENDING_L1   — l1-approve / l1-reject
 *   PENDING_L2   — l2-approve / l2-reject (via shared L2ApprovalButtons)
 *   L2_APPROVED  — mark-posted
 *   POSTED       — terminal (read-only)
 *   REJECTED     — terminal (read-only)
 *
 * Status colors (per plan):
 *   DRAFT        — default
 *   PENDING_L1   — gold
 *   PENDING_L2   — orange
 *   L2_APPROVED  — cyan
 *   POSTED       — blue
 *   REJECTED     — red
 *
 * Backend contract: `BillingAdjustmentController.list/get/create/submit/
 *  l1-approve/l1-reject/l2-approve/l2-reject/mark-posted`.
 * Service wrapper: `services/finance/financeAdjustmentService`.
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
  Select,
  Space,
  Tag,
} from 'antd';
import { useRef, useState } from 'react';

import { DateTimeColumn } from '@/components/columns/DateTimeColumn';
import L2ApprovalButtons from '@/components/finance/L2ApprovalButtons';
import { useAccess } from '@/hooks/useAccess';
import { useAuth } from '@/auth/useAuth';
import { financeAdjustmentService } from '@/services/finance/financeAdjustmentService';
import type {
  AdjustmentStatus,
  AdjustmentType,
  BillingAdjustment,
} from '@/types/finance';
import { formatCurrency, formatRate } from '@/utils/currencyFormatter';

const STATUS_OPTIONS: { value: AdjustmentStatus; label: string }[] = [
  { value: 'DRAFT',       label: '草稿' },
  { value: 'PENDING_L1',  label: '待初审' },
  { value: 'PENDING_L2',  label: '待终审' },
  { value: 'L2_APPROVED', label: 'L2 已通过' },
  { value: 'POSTED',      label: '已过账' },
  { value: 'REJECTED',    label: '已驳回' },
];

const STATUS_COLOR: Record<AdjustmentStatus, string> = {
  DRAFT: 'default',
  PENDING_L1: 'gold',
  PENDING_L2: 'orange',
  L2_APPROVED: 'cyan',
  POSTED: 'blue',
  REJECTED: 'red',
};

const STATUS_LABEL: Record<AdjustmentStatus, string> = {
  DRAFT: '草稿',
  PENDING_L1: '待初审',
  PENDING_L2: '待终审',
  L2_APPROVED: 'L2 已通过',
  POSTED: '已过账',
  REJECTED: '已驳回',
};

interface CreateAdjustmentFormValues {
  sourceInvoiceId: number;
  adjustmentType: AdjustmentType;
  amount: number;
  reason: string;
}

/**
 * 4.5C-2.1 — 把搜索区「有无异议」下拉的取值映射成后端参数。
 *
 * <p>抽成独立纯函数是为了能直接单测这条映射: ProTable 的搜索下拉在 jsdom 里
 * 落在折叠区 (5 个搜索项超过默认折叠阈值), 驱动它的选中态很脆, 而这条映射
 * 恰恰是最容易写错的地方 ——
 *
 * <ul>
 *   <li>不选 (undefined) → **undefined**。必须是 undefined 而不是 false:
 *       axios 会跳过 undefined, 后端因此不过滤; false 会被后端理解成
 *       「只回无异议的」, 于是页面一打开就变成过滤过的了。</li>
 *   <li>'true' → true → 后端只回有异议的。</li>
 *   <li>'false' → false → 后端只回无异议的。漏掉这一支的后果是:
 *       用户选了「无异议」, 参数退化成 undefined, 后端不过滤, 列表里照样
 *       出现有异议的单子, 而用户以为自己筛过了。</li>
 * </ul>
 */
export function toDisputedParam(value: string | undefined): boolean | undefined {
  if (value === 'true') return true;
  if (value === 'false') return false;
  return undefined;
}

/**
 * Adjustment workbench — ProTable with create modal + workflow actions.
 *
 * L2 buttons are rendered via the shared `<L2ApprovalButtons />` so the
 * separation-of-duties guard (current user !== l1Approver) lives in one
 * place.
 */
export default function FinanceAdjustmentList() {
  const actionRef = useRef<ActionType>();
  const { message: antMessage, modal } = AntApp.useApp();
  const access = useAccess();
  const { user } = useAuth();
  const currentUserId = user?.userId;

  const [drawerOpen, setDrawerOpen] = useState(false);
  const [current, setCurrent] = useState<BillingAdjustment | null>(null);
  const [createOpen, setCreateOpen] = useState(false);
  const [form] = Form.useForm<CreateAdjustmentFormValues>();

  const openDetail = async (record: BillingAdjustment) => {
    try {
      const fresh = await financeAdjustmentService.getAdjustment(record.id);
      setCurrent(fresh);
    } catch {
      setCurrent(record);
    }
    setDrawerOpen(true);
  };

  const handleSubmit = async (id: number) => {
    try {
      await financeAdjustmentService.submitAdjustment(id);
      antMessage.success('已提交初审');
      actionRef.current?.reload();
    } catch {
      /* handled */
    }
  };

  const handleL1Approve = async (id: number) => {
    try {
      await financeAdjustmentService.approveAdjustmentL1(id);
      antMessage.success('L1 已通过');
      actionRef.current?.reload();
    } catch {
      /* handled */
    }
  };

  const handleL1Reject = (id: number) => {
    let reason = '';
    modal.confirm({
      title: 'L1 驳回原因',
      content: (
        <textarea
          rows={3}
          style={{ width: '100%', padding: 8, marginTop: 8 }}
          placeholder="请填写驳回原因 (必填)"
          onChange={(e) => {
            reason = e.target.value;
          }}
        />
      ),
      okText: '确认驳回',
      cancelText: '取消',
      okButtonProps: { danger: true },
      onOk: async () => {
        if (!reason.trim()) {
          antMessage.error('驳回原因不能为空');
          throw new Error('reason-required');
        }
        await financeAdjustmentService.rejectAdjustmentL1(id, reason.trim());
        antMessage.success('已驳回');
        actionRef.current?.reload();
      },
    });
  };

  const handleL2Approve = async (id: number) => {
    try {
      await financeAdjustmentService.approveAdjustmentL2(id);
      antMessage.success('L2 已通过');
      actionRef.current?.reload();
    } catch {
      /* handled */
    }
  };

  const handleL2Reject = async (id: number, comment: string) => {
    await financeAdjustmentService.rejectAdjustmentL2(id, comment);
    antMessage.success('已驳回');
    actionRef.current?.reload();
  };

  const handleMarkPosted = async (id: number) => {
    try {
      await financeAdjustmentService.markAdjustmentPosted(id);
      antMessage.success('已过账');
      actionRef.current?.reload();
    } catch {
      /* handled */
    }
  };

  const handleCreate = async () => {
    try {
      const values = await form.validateFields();
      await financeAdjustmentService.createAdjustment({
        sourceInvoiceId: values.sourceInvoiceId,
        adjustmentType: values.adjustmentType,
        amount: values.amount,
        reason: values.reason,
      });
      antMessage.success('已创建调账单 (草稿)');
      setCreateOpen(false);
      form.resetFields();
      actionRef.current?.reload();
    } catch {
      /* handled */
    }
  };

  const columns: ProColumns<BillingAdjustment>[] = [
    { title: '单号', dataIndex: 'adjustmentNo', width: 200 },
    {
      title: '类型',
      dataIndex: 'adjustmentType',
      width: 90,
      valueType: 'select',
      fieldProps: {
        options: [
          { value: 'CREDIT', label: '贷项 (CREDIT)' },
          { value: 'DEBIT',  label: '借项 (DEBIT)' },
        ],
      },
      render: (_, r) =>
        r.adjustmentType === 'CREDIT' ? (
          <Tag color="cyan">贷项</Tag>
        ) : (
          <Tag color="purple">借项</Tag>
        ),
    },
    { title: '源发票ID', dataIndex: 'sourceInvoiceId', width: 100 },
    {
      title: '金额',
      dataIndex: 'amount',
      width: 140,
      hideInSearch: true,
      render: (_, r) => formatCurrency(r.amount, r.currency),
    },
    {
      title: '汇率',
      dataIndex: 'fxRateAtCreation',
      width: 100,
      hideInSearch: true,
      render: (_, r) => formatRate(r.fxRateAtCreation),
    },
    {
      title: '税额',
      dataIndex: 'taxAmount',
      width: 130,
      hideInSearch: true,
      render: (_, r) => formatCurrency(r.taxAmount, r.currency),
    },
    {
      title: '净额',
      dataIndex: 'netAmount',
      width: 130,
      hideInSearch: true,
      render: (_, r) => formatCurrency(r.netAmount, r.currency),
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 110,
      valueType: 'select',
      fieldProps: { options: STATUS_OPTIONS },
      render: (_, r) => (
        <Tag color={STATUS_COLOR[r.status]}>{STATUS_LABEL[r.status]}</Tag>
      ),
    },
    {
      // 4.5C-2.1 — 商家异议。商家提异议后原话写在 t_reconciliation 上, 这里
      // 由后端回填的瞬态字段带过来。财务据此判断"商家在争什么"。
      title: '商家异议',
      dataIndex: 'disputeAt',
      width: 160,
      hideInSearch: true,
      render: (_, r) =>
        r.disputeAt ? (
          <Tag color="orange">
            有异议{r.disputeAmount != null ? ` ${formatCurrency(r.disputeAmount, r.currency)}` : ''}
          </Tag>
        ) : (
          <Tag>无</Tag>
        ),
    },
    {
      // 4.5C-2.1 — 按"有没有商家异议"筛。后端 ?disputed=true 只回有异议的。
      title: '商家异议',
      dataIndex: 'disputed',
      width: 120,
      valueType: 'select',
      // 纯搜索列 —— 表格体里不占一列 (hideInTable 是 ProTable 自带的字段,
      // 与 hideInSearch 是一对; 用它比 render: () => null 干净:
      // 后者会留一个空表头 + 一列空单元格)。
      hideInTable: true,
      fieldProps: {
        options: [
          { value: 'true', label: '有异议' },
          { value: 'false', label: '无异议' },
        ],
      },
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
      width: 320,
      render: (_, record) => (
        <Space size="small" wrap>
          <Button
            key="detail"
            type="link"
            size="small"
            disabled={!access.canRead('finance:adjustment:view')}
            onClick={() => openDetail(record)}
          >
            详情
          </Button>
          {record.status === 'DRAFT' && (
            <Button
              key="submit"
              type="link"
              size="small"
              disabled={!access.canRead('finance:adjustment:submit')}
              onClick={() => handleSubmit(record.id)}
            >
              提交初审
            </Button>
          )}
          {record.status === 'PENDING_L1' && (
            <>
              <Button
                key="l1-approve"
                type="link"
                size="small"
                disabled={!access.canRead('finance:adjustment:l1_approve')}
                onClick={() => handleL1Approve(record.id)}
              >
                通过
              </Button>
              <Button
                key="l1-reject"
                type="link"
                size="small"
                danger
                disabled={!access.canRead('finance:adjustment:l1_approve')}
                onClick={() => handleL1Reject(record.id)}
              >
                驳回
              </Button>
            </>
          )}
          {record.status === 'PENDING_L2' && (
            <L2ApprovalButtons
              currentStatus={record.status}
              l1ApproverId={record.l1ApproverId}
              currentUserId={currentUserId}
              perm="finance:adjustment:l2_approve"
              onL2Approve={() => handleL2Approve(record.id)}
              onL2Reject={(comment) => handleL2Reject(record.id, comment)}
            />
          )}
          {record.status === 'L2_APPROVED' && (
            <Button
              key="mark-posted"
              type="link"
              size="small"
              disabled={!access.canRead('finance:adjustment:l2_approve')}
              onClick={() => handleMarkPosted(record.id)}
            >
              确认过账
            </Button>
          )}
        </Space>
      ),
    },
  ];

  return (
    <>
      <ProTable<BillingAdjustment>
        headerTitle="调账单"
        actionRef={actionRef}
        rowKey="id"
        columns={columns}
        search={{ labelWidth: 'auto' }}
        request={async (params) => {
          const res = await financeAdjustmentService.listAdjustments({
            status: params.status,
            // 4.5C-2.1 — 不选筛选时必须是 undefined (axios 会跳过 undefined),
            // 不能是 false —— false 会被后端理解成"只回无异议的", 默认就变成过滤了。
            // 映射逻辑见 toDisputedParam (那里有单测锁住 true/false/不选 三条路)。
            disputed: toDisputedParam(params.disputed as string | undefined),
            page: params.current ?? 1,
            size: params.pageSize ?? 20,
          });
          return { data: res.records, total: res.total, success: true };
        }}
        pagination={{ defaultPageSize: 20, showSizeChanger: true }}
        toolBarRender={() => [
          <Button
            key="add"
            type="primary"
            disabled={!access.canRead('finance:adjustment:create')}
            onClick={() => {
              form.resetFields();
              form.setFieldsValue({ adjustmentType: 'CREDIT' });
              setCreateOpen(true);
            }}
          >
            新建调账单
          </Button>,
        ]}
      />

      <Drawer
        title={current ? `调账单详情 — ${current.adjustmentNo}` : '调账单详情'}
        open={drawerOpen}
        onClose={() => setDrawerOpen(false)}
        width={560}
      >
        {current && (
          <Descriptions column={1} bordered size="small">
            <Descriptions.Item label="ID">{current.id}</Descriptions.Item>
            <Descriptions.Item label="单号">{current.adjustmentNo}</Descriptions.Item>
            <Descriptions.Item label="类型">
              <Tag color={current.adjustmentType === 'CREDIT' ? 'cyan' : 'purple'}>
                {current.adjustmentType === 'CREDIT' ? '贷项' : '借项'}
              </Tag>
            </Descriptions.Item>
            <Descriptions.Item label="源发票ID">
              {current.sourceInvoiceId}
            </Descriptions.Item>
            <Descriptions.Item label="状态">
              <Tag color={STATUS_COLOR[current.status]}>
                {STATUS_LABEL[current.status]}
              </Tag>
            </Descriptions.Item>
            <Descriptions.Item label="金额 (含税)">
              {formatCurrency(current.amount, current.currency)}
            </Descriptions.Item>
            <Descriptions.Item label="汇率 (创建时)">
              {formatRate(current.fxRateAtCreation)}
            </Descriptions.Item>
            <Descriptions.Item label="税率">
              {(current.taxRate * 100).toFixed(2)}%
            </Descriptions.Item>
            <Descriptions.Item label="税额">
              {formatCurrency(current.taxAmount, current.currency)}
            </Descriptions.Item>
            <Descriptions.Item label="净额">
              {formatCurrency(current.netAmount, current.currency)}
            </Descriptions.Item>
            <Descriptions.Item label="原因">{current.reason ?? '—'}</Descriptions.Item>
            {/*
              4.5C-2.1 — 商家异议原话。"原因"那一项是 runReconciliation 自动生成的
              「对账差异自动生成: …」, 商家真正在争的理由在这一项。
            */}
            <Descriptions.Item label="商家异议理由">
              {current.disputeReason ?? '—'}
            </Descriptions.Item>
            <Descriptions.Item label="商家主张金额">
              {current.disputeAt
                ? current.disputeAmount != null
                  ? formatCurrency(current.disputeAmount, current.currency)
                  : '未主张金额 (仅质疑)'
                : '—'}
            </Descriptions.Item>
            <Descriptions.Item label="异议时间">
              {current.disputeAt ? <DateTimeColumn value={current.disputeAt} /> : '—'}
            </Descriptions.Item>
            <Descriptions.Item label="创建时间">
              <DateTimeColumn value={current.createdAt} />
            </Descriptions.Item>
            {current.l1ApproverId != null && (
              <Descriptions.Item label="L1 审批">
                #{current.l1ApproverId} ·{' '}
                <DateTimeColumn value={current.l1ApprovedAt} />
                {current.l1Comment && ` · ${current.l1Comment}`}
              </Descriptions.Item>
            )}
            {current.l2ApproverId != null && (
              <Descriptions.Item label="L2 审批">
                #{current.l2ApproverId} ·{' '}
                <DateTimeColumn value={current.l2ApprovedAt} />
                {current.l2Comment && ` · ${current.l2Comment}`}
              </Descriptions.Item>
            )}
            {current.errorMsg && (
              <Descriptions.Item label="错误">
                <span style={{ color: '#cf1322' }}>{current.errorMsg}</span>
              </Descriptions.Item>
            )}
          </Descriptions>
        )}
      </Drawer>

      <Modal
        title="新建调账单"
        open={createOpen}
        onOk={handleCreate}
        onCancel={() => setCreateOpen(false)}
        okText="创建"
        cancelText="取消"
        destroyOnClose
        width={520}
      >
        <Form<CreateAdjustmentFormValues> form={form} layout="vertical">
          <Form.Item
            name="sourceInvoiceId"
            label="源发票 ID"
            rules={[{ required: true, message: '请输入源发票 ID' }]}
          >
            <InputNumber min={1} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item
            name="adjustmentType"
            label="类型"
            rules={[{ required: true, message: '请选择类型' }]}
          >
            <Select
              options={[
                { value: 'CREDIT', label: '贷项 (CREDIT)' },
                { value: 'DEBIT',  label: '借项 (DEBIT)' },
              ]}
              placeholder="请选择"
            />
          </Form.Item>
          <Form.Item
            name="amount"
            label="金额"
            rules={[{ required: true, message: '请输入金额' }]}
          >
            <InputNumber min={0} step={0.01} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item
            name="reason"
            label="原因"
            rules={[{ required: true, message: '请填写原因' }]}
          >
            <Input.TextArea rows={3} placeholder="例如: 货损赔偿 / 多计金额调整" />
          </Form.Item>
        </Form>
      </Modal>
    </>
  );
}