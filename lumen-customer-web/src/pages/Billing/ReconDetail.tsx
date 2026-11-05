import { useEffect, useState } from 'react';
import { Alert, Button, Card, Descriptions, Form, Input, InputNumber, Space, Spin, message } from 'antd';
import { useNavigate, useParams } from 'react-router-dom';
import { portalBillingService } from '../../services/portalBillingService';
import type { ReconView } from '../../types/billing';
import { money } from '../../utils/money';
import { zhCN } from '../../locale/zh-CN';

/**
 * 4.5C-2 — 对账详情 + 提异议.
 *
 * 提异议的可提交条件三条同时满足: **已闭期** / 本期未异议过 / 理由非空。
 *
 * ⚠️ 闭期闸门的方向是「**已闭期才可提**」—— 未闭期 (无月结记录 / OPEN /
 * IN_PROGRESS) 时这一期的数字还会变, 提异议没有意义, 后端直接 409_002。
 * 4.5C-2 最初把这条写反了 (前后端 + 文案 + 测试一起反), 后端已在 888050c
 * 订正, 这里同步对齐 spec §7.2。
 *
 * 未闭期与已异议走后端也拒 (409) —— 前端直接**不渲染**异议表单 (不是把按钮置灰),
 * 让商家少撞一次墙。
 */
export default function ReconDetail() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const reconId = Number(id);
  const [recon, setRecon] = useState<ReconView | null>(null);
  const [loading, setLoading] = useState(true);
  const [notFound, setNotFound] = useState(false);
  const [loadFailed, setLoadFailed] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [form] = Form.useForm<{ reason: string; claimedAmount?: number | null }>();

  useEffect(() => {
    let alive = true;
    // reconId 变了先把上一张的残留清掉 —— React Router 同路由换参数不卸载组件,
    // 不清的话商家会对着上一期的金额提异议。
    setRecon(null);
    setNotFound(false);
    setLoadFailed(false);
    setLoading(true);
    portalBillingService
      .reconDetail(reconId)
      .then((r) => {
        if (alive) setRecon(r);
      })
      .catch((e) => {
        // 404 = 这张对账单不存在; 其余 (断网 / 500 / 403) = 加载失败。
        // 两者对商家的含义完全不同, 混成一句"对账单不存在"会把网络故障说成单子被删了。
        if (!alive) return;
        if ((e as { response?: { status?: number } })?.response?.status === 404) {
          setNotFound(true);
        } else {
          setLoadFailed(true);
        }
      })
      .finally(() => {
        if (alive) setLoading(false);
      });
    return () => {
      alive = false;
    };
  }, [reconId]);

  const onSubmit = async (values: { reason: string; claimedAmount?: number | null }) => {
    setSubmitting(true);
    try {
      const out = await portalBillingService.raiseDispute(reconId, {
        reason: values.reason,
        claimedAmount: values.claimedAmount ?? null,
      });
      message.success(zhCN.billing.disputeSubmitted(zhCN.billing.statusText(out.adjustmentStatus)));
      // spec §9.4: 提交成功后跳「我的调账单」并显示 PENDING_L1 ——
      // 商家提完异议最想知道的是"这笔异议现在到哪一步了", 那张调账单就是答案。
      navigate('/adjustments');
    } catch (e) {
      // 后端把每种业务拒绝翻成独立的门户码 (400_003 / 400_004 / 400_005 / 409_002),
      // 每种拒绝对商家的下一步动作都不同 —— 一律吞成"请稍后重试"等于让商家瞎猜。
      // 不直接透传后端 message: 409_002 那条 message 是 finance 的英文内部串
      // ("Period 2026-08 is already CLOSED, dispute window closed"), 摊给商家更难懂。
      message.error(disputeErrorText(e));
    } finally {
      setSubmitting(false);
    }
  };

  if (loading) return <Spin />;
  if (notFound) return <Alert type="error" showIcon message={zhCN.billing.reconNotFound} />;
  if (loadFailed || !recon) {
    return <Alert type="error" showIcon message={zhCN.billing.loadFailed} />;
  }

  // 走到这里 recon 一定非 null —— TS 能收窄, 不用写 recon!
  const disputed = Boolean(recon.disputeAt);
  // 已闭期才可提异议 (spec §7.2) —— 未闭期数字还在变, 提了也没有意义。
  const canDispute = recon.closed && !disputed;

  return (
    <Space direction="vertical" style={{ width: '100%' }} size="middle">
      <Card title={`${zhCN.billing.reconTitle} — ${recon.period ?? '-'}`}>
        <Descriptions column={2} bordered size="small">
          <Descriptions.Item label={zhCN.billing.expected}>
            {money(recon.expectedAmount, recon.currency)}
          </Descriptions.Item>
          <Descriptions.Item label={zhCN.billing.actual}>
            {money(recon.actualAmount, recon.currency)}
          </Descriptions.Item>
          <Descriptions.Item label={zhCN.billing.variance}>
            {money(recon.varianceAmount, recon.currency)}
          </Descriptions.Item>
          <Descriptions.Item label={zhCN.billing.periodClosed}>
            {recon.closed ? zhCN.billing.closed : zhCN.billing.dataMayChange}
          </Descriptions.Item>
          {/* 对账状态 —— 商家靠它判断"我这笔差异现在到哪一步了"。
              状态不是 ADJUSTMENT_CREATED 的行商家提不了异议 (后端闸门),
              让他看得见原因比让他撞一次 409 强。 */}
          <Descriptions.Item label={zhCN.billing.reconStatus}>
            {zhCN.billing.statusText(recon.reconciliationStatus)}
          </Descriptions.Item>
        </Descriptions>
      </Card>

      {!recon.closed && (
        <Alert type="warning" showIcon message={zhCN.billing.notClosedNoDispute} />
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
          {/* preserve={false}: antd 的 Form 默认 preserve=true —— 加载期间整块
              卸载 Form, 但 store 留着, 换期回来时上一期的理由和主张金额会原样
              还在输入框里, 商家会拿着上一期的金额提这一期的异议。
              preserve=false 让 Form 卸载时连值一起清掉。 */}
          <Form<{ reason: string; claimedAmount?: number | null }>
            form={form}
            layout="vertical"
            preserve={false}
            onFinish={onSubmit}
          >
            <Form.Item
              name="reason"
              label={zhCN.billing.disputeReason}
              rules={[
                {
                  required: true,
                  // 后端 isBlank() 判定: 光敲空格在服务端是 400_003,
                  // 前端必须一起拦, 否则商家会以为自己填了却被拒。
                  whitespace: true,
                  message: zhCN.billing.disputeReasonRequired,
                },
                {
                  // 理由最短 10 字 (后端 FinanceConstants.ADJUSTMENT_REASON_MIN_LEN) ——
                  // 一条要进 L1/L2 审批的异议撑不起太短的理由, 后端会 400_003。
                  // 前端提前挡 (spec §9.4「异议表单的前端校验」)。
                  //
                  // 用自定义 validator 而不是内置 min 规则: 后端是 **trim 之后**
                  // 再判长度, 内置 min 按原始串判 —— ' abcdefghi ' (11 字, trim 后 9 字)
                  // 前端会放行, 后端却拒。两边口径必须一致, 所以这里也按 trim 判。
                  message: zhCN.billing.disputeReasonTooShort,
                  validator: (_rule, value: string | undefined) => {
                    const trimmed = (value ?? '').trim();
                    // 空 / 纯空格交给上面那条 required+whitespace 规则报,
                    // 这里不重复报 —— 免得商家看到两条互相矛盾的提示。
                    if (trimmed.length === 0) return Promise.resolve();
                    return trimmed.length < 10
                      ? Promise.reject(new Error(zhCN.billing.disputeReasonTooShort))
                      : Promise.resolve();
                  },
                },
              ]}
            >
              <Input.TextArea rows={3} maxLength={500} showCount />
            </Form.Item>
            <Form.Item
              name="claimedAmount"
              label={zhCN.billing.disputeAmount}
              extra={zhCN.billing.disputeAmountHint}
              rules={[
                {
                  // 主张金额 ≠ 0 (后端 PortalReconController 同口径, 走 400_004)。
                  //
                  // ⚠️ **负数必须放行** —— 商家主张的是差额, 财务算出的 variance
                  // 是负的就该是负的 (实收 > 应收, 商家要退钱)。所以这里判的是
                  // "等于 0", 不是 "小于 0"; 也不能写 min: 0.01。
                  // 留空 (null / undefined) = 只质疑不主张金额, 合法。
                  message: zhCN.billing.disputeAmountZero,
                  validator: (_rule, value: number | null | undefined) =>
                    value === 0
                      ? Promise.reject(new Error(zhCN.billing.disputeAmountZero))
                      : Promise.resolve(),
                },
              ]}
            >
              {/* precision={4}: 后端 dispute_amount 是 DECIMAL(18,4) (scale <= 4),
                  商家填第 5 位小数会被 400_004 拒。前端直接限死, 少一次白跑。 */}
              <InputNumber
                precision={4}
                style={{ width: '100%' }}
                placeholder={zhCN.billing.disputeAmountPlaceholder}
              />
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

/**
 * 门户错误码 → 商家看得懂的下一步。
 *
 * 码值见 PortalErrorCode.java: 400_003 理由为空 / 理由少于 10 字 / 400_004 金额超范围
 * 或为 0 / 400_005 参数非法 / 409_002 该期不可提异议 (期间未闭期 / 状态非
 * ADJUSTMENT_CREATED / 已异议过 / 无关联调账单)。
 * 403 没有独立门户码 (走 Spring 的权限校验), 单独按 HTTP status 分流。
 *
 * **为什么不透传后端 message**: 409_002 走 finance 侧翻译时 message 是 finance 的
 * 英文内部串, 其余几个是门户自己的中文文案。混着透传会让一部分商家看到英文,
 * 所以统一由前端按码分流。
 */
function disputeErrorText(e: unknown): string {
  const code = (e as { response?: { data?: { code?: number } } })?.response?.data?.code;
  switch (code) {
    case 400003:
      return zhCN.billing.disputeReasonRequired;
    case 400004:
      return zhCN.billing.disputeAmountInvalid;
    case 400005:
      return zhCN.billing.disputeParamInvalid;
    case 409002:
      return zhCN.billing.disputeNotDisputable;
    default:
      // 403 = 没有 customer:adjustment:create 权限 —— 重试无用, 得说清楚。
      return (e as { response?: { status?: number } })?.response?.status === 403
        ? zhCN.billing.disputeForbidden
        : zhCN.billing.disputeFailed;
  }
}
