import { http } from './http';
import type { ApiResponse } from '../types/portal';
import type {
  PortalEstimateResult,
  PortalCreateOrderRequest,
  PortalCancelOrderRequest,
  PortalOrderDetail,
  PortalOrderListQuery,
  PortalOrderListData,
} from '../types/order';

/**
 * 4.5B.3-T3.1 — Portal order service.
 *
 * Endpoints:
 *   POST /api/customer/order/preview           → estimate (no DB write)
 *   POST /api/customer/order                   → create new PENDING_DISPATCH order
 *   GET  /api/customer/order                   → paginated list (server filters by customer_id from JWT)
 *   GET  /api/customer/order/{doId}            → detail (cross-customer → 404)
 *   POST /api/customer/order/{doId}/cancel     → cancel only if PENDING_DISPATCH
 *
 * http baseURL is "/api", so paths below omit the /api prefix.
 */

const BASE = '/customer/order';

export const portalOrderService = {
  /** 1. 估价预览 — 不写库, 仅返回 EstimateResult. */
  async preview(req: PortalCreateOrderRequest): Promise<PortalEstimateResult> {
    const r = await http.post<ApiResponse<PortalEstimateResult>>(
      `${BASE}/preview`,
      req
    );
    return r.data.data;
  },

  /** 2. 创建订单 (PENDING_DISPATCH). */
  async create(req: PortalCreateOrderRequest): Promise<PortalOrderDetail> {
    const r = await http.post<ApiResponse<PortalOrderDetail>>(BASE, req);
    return r.data.data;
  },

  /** 3. 订单列表. */
  async list(query: PortalOrderListQuery): Promise<PortalOrderListData> {
    const r = await http.get<ApiResponse<PortalOrderListData>>(BASE, {
      params: query,
    });
    return r.data.data;
  },

  /** 4. 订单详情. */
  async detail(doId: number): Promise<PortalOrderDetail> {
    const r = await http.get<ApiResponse<PortalOrderDetail>>(`${BASE}/${doId}`);
    return r.data.data;
  },

  /** 5. 取消订单 (PENDING_DISPATCH 才允许). */
  async cancel(
    doId: number,
    req: PortalCancelOrderRequest
  ): Promise<PortalOrderDetail> {
    const r = await http.post<ApiResponse<PortalOrderDetail>>(
      `${BASE}/${doId}/cancel`,
      req
    );
    return r.data.data;
  },
};