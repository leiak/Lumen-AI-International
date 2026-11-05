import { http } from './http';
import type { ApiResponse } from '../types/portal';
import type {
  MerchantPage,
  ReconView,
  InvoiceView,
  PaymentView,
  AdjustmentView,
  DisputeRequest,
  DisputeResult,
} from '../types/billing';

/**
 * 4.5C-2 — Portal billing service.
 *
 * Endpoints:
 *   GET  /api/customer/recon            → 对账列表
 *   GET  /api/customer/recon/{id}       → 对账详情
 *   GET  /api/customer/recon/export     → 对账 xlsx (二进制)
 *   POST /api/customer/recon/dispute     → 提异议 (reconId 在 body)
 *   GET  /api/customer/invoice                 → 发票列表
 *   GET  /api/customer/invoice/{id}            → 发票详情
 *   GET  /api/customer/invoice/export          → 发票 xlsx (二进制)
 *   GET  /api/customer/payment                 → 付款列表
 *   GET  /api/customer/adjustment              → 我的调账单
 *   GET  /api/customer/adjustment/{id}         → 调账单详情
 *
 * http baseURL 是 "/api", 所以下面不带 /api 前缀。
 * 导出走 responseType: 'blob' —— 走默认 JSON 解析会把 xlsx 解成一堆乱码字符。
 */

const RECON = '/customer/recon';
const INVOICE = '/customer/invoice';
const PAYMENT = '/customer/payment';
const ADJUSTMENT = '/customer/adjustment';

/** 触发浏览器下载。后缀留给调用方, 避免这里硬编码成 xlsx —— 将来加 CSV 时不用改。 */
function saveBlob(data: Blob, filename: string): void {
  const url = URL.createObjectURL(data);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  // 下载由浏览器下载管理器**异步**取流, 同一个 task 里 revoke 可能把它掐掉
  // (Safari 已知会失败, Chromium 有 "revokes blob too early" 的官方 issue)。
  // 延一 tick 再撤, 代价为零。
  setTimeout(() => URL.revokeObjectURL(url), 0);
}

export const portalBillingService = {
  async listRecons(period?: string): Promise<MerchantPage<ReconView>> {
    const r = await http.get<ApiResponse<MerchantPage<ReconView>>>(RECON, {
      params: period ? { period } : undefined,
    });
    return r.data.data;
  },

  async reconDetail(id: number): Promise<ReconView> {
    const r = await http.get<ApiResponse<ReconView>>(`${RECON}/${id}`);
    return r.data.data;
  },

  async exportRecons(period?: string): Promise<void> {
    const r = await http.get(`${RECON}/export`, {
      params: period ? { period } : undefined,
      responseType: 'blob',
    });
    saveBlob(r.data as Blob, '对账单.xlsx');
  },

  async raiseDispute(reconId: number, req: DisputeRequest): Promise<DisputeResult> {
    const r = await http.post<ApiResponse<DisputeResult>>(`${RECON}/dispute`, {
      reconId,
      ...req,
    });
    return r.data.data;
  },

  async listInvoices(params?: {
    period?: string;
    status?: string;
  }): Promise<MerchantPage<InvoiceView>> {
    const r = await http.get<ApiResponse<MerchantPage<InvoiceView>>>(INVOICE, { params });
    return r.data.data;
  },

  async invoiceDetail(id: number): Promise<InvoiceView> {
    const r = await http.get<ApiResponse<InvoiceView>>(`${INVOICE}/${id}`);
    return r.data.data;
  },

  async exportInvoices(params?: {
    period?: string;
    status?: string;
  }): Promise<void> {
    const r = await http.get(`${INVOICE}/export`, {
      params,
      responseType: 'blob',
    });
    saveBlob(r.data as Blob, '发票.xlsx');
  },

  async listPayments(period?: string): Promise<MerchantPage<PaymentView>> {
    const r = await http.get<ApiResponse<MerchantPage<PaymentView>>>(PAYMENT, {
      params: period ? { period } : undefined,
    });
    return r.data.data;
  },

  async listAdjustments(): Promise<MerchantPage<AdjustmentView>> {
    const r = await http.get<ApiResponse<MerchantPage<AdjustmentView>>>(ADJUSTMENT);
    return r.data.data;
  },

  async adjustmentDetail(id: number): Promise<AdjustmentView> {
    const r = await http.get<ApiResponse<AdjustmentView>>(`${ADJUSTMENT}/${id}`);
    return r.data.data;
  },
};
