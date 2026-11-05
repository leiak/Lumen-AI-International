/**
 * 4.5C-2 — 商家门户账单类型.
 *
 * 对应后端 DTO:
 *   lumen-parent/lumen-finance/src/main/java/com/lumen/finance/dto/portal/MerchantDtos.java
 *   lumen-parent/lumen-finance/src/main/java/com/lumen/finance/dto/portal/MerchantPage.java
 *
 * 字段名用 camelCase 对齐 Jackson 默认策略; amount 一律 number | null ——
 * 后端 BigDecimal null 会序列化成 null 而不是 0, 前端要能区分
 * "0 元" 与 "没有这个数"。
 */

/** 后端 MerchantPage<T> 的分页信封。 */
export interface MerchantPage<T> {
  records: T[];
  total: number;
  page: number;
  size: number;
}

/**
 * 对账状态 (t_reconciliation.reconciliation_status)。
 *
 * 后端 FinanceConstants 的 RECON_STATUS_* 四值。后端 DTO 字段类型是 String,
 * 所以这里保持 `string | null` 而不是收窄成联合类型 —— 存量脏数据出现第五个值时
 * 前端要能原样透传 (locale.statusText 对未收录的码原样返回), 而不是崩掉。
 */
export type ReconciliationStatus = 'PENDING' | 'ADJUSTMENT_CREATED' | 'RESOLVED' | 'WAIVED';

/** GET /api/customer/recon/{id} 与列表行。 */
export interface ReconView {
  id: number;
  period: string | null;
  currency: string | null;
  expectedAmount: number | null;
  actualAmount: number | null;
  varianceAmount: number | null;
  adjustmentId: number | null;
  /**
   * 对账状态 —— 取值见 {@link ReconciliationStatus}:
   * PENDING (还没跑过对账) / ADJUSTMENT_CREATED (已生成调账单, **唯一可提异议的状态**) /
   * RESOLVED (已结清) / WAIVED (已豁免)。
   */
  reconciliationStatus: string | null;
  remark: string | null;
  disputeAmount: number | null;
  disputeReason: string | null;
  disputeAt: string | null;
  /**
   * 该会计期间是否已 CLOSED。
   *
   * **已闭期才可提异议** —— 未闭期 (无月结记录 / OPEN / IN_PROGRESS) 时这一期的
   * 数字还会变, 提异议没有意义, 后端回 409_002。
   */
  closed: boolean;
}

/** GET /api/customer/invoice。 */
export interface InvoiceView {
  id: number;
  invoiceNo: string | null;
  period: string | null;
  businessType: string | null;
  grossAmount: number | null;
  taxAmount: number | null;
  netAmount: number | null;
  currency: string | null;
  fxRateAtCreation: number | null;
  status: string | null;
  createdAt: string | null;
  /**
   * L1 审批意见 —— 商家必须看得到, 否则审批驳回就成了黑洞。
   *
   * 注意后端 l1Reject (驳回) 路径会拒空白意见, 但 l1Approve (通过) 路径
   * **不校验** —— 可能落 "" 而不是 NULL。所以前端取意见必须用 `||` 而不是 `??`
   * (空串是 falsy, 会正确回落到下一级 / 「暂无」)。
   */
  l1Comment: string | null;
  l2Comment: string | null;
}

/** GET /api/customer/payment。 */
export interface PaymentView {
  id: number;
  period: string | null;
  currency: string | null;
  amount: number | null;
  sourceRef: string | null;
  paymentDate: string | null;
  source: string | null;
  remark: string | null;
}

/** GET /api/customer/adjustment。 */
export interface AdjustmentView {
  id: number;
  adjustmentNo: string | null;
  adjustmentType: string | null;
  amount: number | null;
  currency: string | null;
  grossAmount: number | null;
  taxAmount: number | null;
  netAmount: number | null;
  status: string | null;
  reason: string | null;
  createdAt: string | null;
  /**
   * L1 审批意见 —— 商家提了异议就必须看得到审批意见 (驳回时是驳回理由)。
   *
   * 同 InvoiceView: 通过路径 (l1Approve / l2Approve) 不校验空白, 可能落 "" 而不是
   * NULL, 所以前端取值要用 `||` 而不是 `??`。
   */
  l1Comment: string | null;
  l2Comment: string | null;
}

/** POST /api/customer/recon/dispute 的响应。 */
export interface DisputeResult {
  reconId: number;
  adjustmentId: number | null;
  adjustmentStatus: string | null;
  disputeAt: string | null;
}

/** POST /api/customer/recon/dispute 的请求体。 */
export interface DisputeRequest {
  claimedAmount?: number | null;
  reason: string;
}
