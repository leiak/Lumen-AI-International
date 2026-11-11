import { http } from './http';
import type { ApiResponse } from '../types/portal';
import type {
  FreightOrderCreateRequest,
  FreightOrderDetail,
  FreightOrderListQuery,
  FreightOrderPageData,
  FreightOrderRow,
} from '../types/freightOrder';

/**
 * 4.6 — 门户端货运委托单 service。
 *
 * 后端: `lumen-parent/lumen-customer/.../controller/PortalFreightOrderController.java`
 * 路径前缀 `/api/customer/freight-order`, 5 个端点。
 *
 * http baseURL 是 "/api", 所以下面的路径**不带** /api 前缀。
 *
 * 与既有 `/customer/order` (城配单) **完全分开** —— 两个业务互不影响。
 */

const BASE = '/customer/freight-order';

export const portalFreightOrderService = {
  /** 1. 我的委托单列表 (后端按 JWT 里的客户过滤)。 */
  async list(query: FreightOrderListQuery): Promise<FreightOrderPageData> {
    const r = await http.get<ApiResponse<FreightOrderPageData>>(BASE, {
      params: query,
    });
    return r.data.data;
  },

  /** 2. 详情 (跨客户 → 404)。 */
  async detail(id: number): Promise<FreightOrderDetail> {
    const r = await http.get<ApiResponse<FreightOrderDetail>>(`${BASE}/${id}`);
    return r.data.data;
  },

  /** 3. 下单 (落 DRAFT)。 */
  async create(req: FreightOrderCreateRequest): Promise<FreightOrderRow> {
    const r = await http.post<ApiResponse<FreightOrderRow>>(BASE, req);
    return r.data.data;
  },

  /** 4. 确认 (PENDING_CONFIRM → CONFIRMED)。 */
  async confirm(id: number): Promise<FreightOrderDetail> {
    const r = await http.post<ApiResponse<FreightOrderDetail>>(
      `${BASE}/${id}/confirm`
    );
    return r.data.data;
  },

  /** 5. 取消 (PENDING_CONFIRM → CANCELLED), 必须带原因。 */
  async cancel(id: number, reason: string): Promise<FreightOrderDetail> {
    const r = await http.post<ApiResponse<FreightOrderDetail>>(
      `${BASE}/${id}/cancel`,
      { reason }
    );
    return r.data.data;
  },
};
