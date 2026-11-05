import { useEffect, useState } from 'react';
import { Alert, Button, Card, Descriptions, Form, Input, InputNumber, Space, Spin, message } from 'antd';
import { useNavigate, useParams } from 'react-router-dom';
import { portalBillingService } from '../../services/portalBillingService';
import type { ReconView } from '../../types/billing';
import { zhCN } from '../../locale/zh-CN';

/**
 * 4.5C-2 — 对账详情 + 提异议.
 *
 * 提异议的可提交条件三条同时满足: 未闭期 / 本期未异议过 / 理由非空。
 * 闭期与已异议走后端也拒 (409), 前端只是提前把按钮置灰, 让商家少撞一次墙。
 */
export default function ReconDetail() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const reconId = Number(id);
  const [recon, setRecon] = useState<ReconView | null>(null);
  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [form] = Form.useForm();

  useEffect(() => {
    let alive = true;
    portalBillingService
      .reconDetail(reconId)
      .then((r) => {
        if (alive) setRecon(r);
      })
      .catch(() => {
        // 详情取不到 (404 / 网络故障) 一律落到下面的空态, 不往上抛。
        if (alive) setRecon(null);
      })
      .finally(() => {
        if (alive) setLoading(false);
      });
    return () => {
      alive = false;
    };
  }, [reconId]);

  const disputed = Boolean(recon?.disputeAt);
  const canDispute = Boolean(recon) && !recon!.closed && !disputed;

  const onSubmit = async (values: { reason: string; claimedAmount?: number | null }) => {
    setSubmitting(true);
    try {
      const out = await portalBillingService.raiseDispute(reconId, {
        reason: values.reason,
        claimedAmount: values.claimedAmount ?? null,
      });
      message.success(zhCN.billing.disputeSubmitted(out.adjustmentStatus ?? ''));
      navigate('/reconciliation');
    } catch {
      message.error(zhCN.billing.disputeFailed);
    } finally {
      setSubmitting(false);
    }
  };

  if (loading) return <Spin />;
  if (!recon) return <Alert type="error" message={zhCN.billing.reconNotFound} />;

  return (
    <Space direction="vertical" style={{ width: '100%' }} size="middle">
      <Card title={`${zhCN.billing.reconTitle} — ${recon.period ?? '-'}`}>
        <Descriptions column={2} bordered size="small">
          <Descriptions.Item label={zhCN.billing.expected}>
            {recon.expectedAmount ?? '-'} {recon.currency}
          </Descriptions.Item>
          <Descriptions.Item label={zhCN.billing.actual}>
            {recon.actualAmount ?? '-'} {recon.currency}
          </Descriptions.Item>
          <Descriptions.Item label={zhCN.billing.variance}>
            {recon.varianceAmount ?? '-'} {recon.currency}
          </Descriptions.Item>
          <Descriptions.Item label={zhCN.billing.periodClosed}>
            {recon.closed ? zhCN.billing.closed : zhCN.billing.open}
          </Descriptions.Item>
        </Descriptions>
      </Card>

      {recon.closed && (
        <Alert type="info" showIcon message={zhCN.billing.closedNoDispute} />
      )}
      {disputed && (
        <Alert
          type="warning"
          showIcon
          message={zhCN.billing.alreadyDisputed}
          description={recon.disputeReason ?? undefined}
        />
      )}

      {canDispute && (
        <Card title={zhCN.billing.raiseDispute}>
          <Form form={form} layout="vertical" onFinish={onSubmit}>
            <Form.Item
              name="reason"
              label={zhCN.billing.disputeReason}
              rules={[{ required: true, message: zhCN.billing.disputeReasonRequired }]}
            >
              <Input.TextArea rows={3} maxLength={500} showCount />
            </Form.Item>
            <Form.Item
              name="claimedAmount"
              label={zhCN.billing.disputeAmount}
              extra={zhCN.billing.disputeAmountHint}
            >
              <InputNumber style={{ width: '100%' }} placeholder={zhCN.billing.disputeAmountPlaceholder} />
            </Form.Item>
            <Button type="primary" htmlType="submit" loading={submitting}>
              {zhCN.billing.submitDispute}
            </Button>
          </Form>
        </Card>
      )}
    </Space>
  );
}
