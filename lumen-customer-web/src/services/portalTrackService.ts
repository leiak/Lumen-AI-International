import { http } from './http';
import type {
  ApiResponse,
  PortalPodResponse,
  PortalTrackDetail,
  PortalTrackListItem,
} from '../types/portal';

/**
 * 4.5A.4-T4.2 — Portal track service.
 *
 * - list: paginated delivery-order lookup scoped to the active customer
 *   (server applies customer_id filter from JWT via CustomerContextFilter).
 * - detail: full DO record + timeline; ownership-validated server-side
 *   (cross-customer access returns 404).
 * - pod: proof-of-delivery payload; same ownership guard.
 */

export interface PortalTrackListParams {
  doNo?: string;
  status?: string;
  page: number;
  size: number;
}

export interface PortalTrackListData {
  records: PortalTrackListItem[];
  total: number;
}

export const portalTrackService = {
  async list(params: PortalTrackListParams): Promise<PortalTrackListData> {
    const r = await http.get<ApiResponse<PortalTrackListData>>(
      '/customer/track',
      { params }
    );
    return r.data.data;
  },

  async detail(doId: number): Promise<PortalTrackDetail> {
    const r = await http.get<ApiResponse<PortalTrackDetail>>(
      `/customer/track/${doId}`
    );
    return r.data.data;
  },

  async pod(doId: number): Promise<PortalPodResponse> {
    const r = await http.get<ApiResponse<PortalPodResponse>>(
      `/customer/track/${doId}/pod`
    );
    return r.data.data;
  },
};
