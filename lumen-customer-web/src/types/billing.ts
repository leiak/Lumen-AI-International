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

/** GET /api/customer/recon/{id} 与列表行。 */
export interface ReconView {
  id: number;
  period: string | null;
  currency: string | null;
  expectedAmount: number | null;
  actualAmount: number | null;
  varianceAmount: number | null;
  adjustmentId: number | null;
  reconciliationStatus: string | null;
  remark: string | null;
  disputeAmount: number | null;
  disputeReason: string | null;
  disputeAt: string | null;
  /** 该会计期间是否已 CLOSED —— false 才能提异议 */
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
  /** L1 驳回理由 —— 商家必须看得到, 否则审批驳回就成了黑洞 */
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
  /** L1 驳回理由 —— 商家提了异议就必须看得到驳回理由 */
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
