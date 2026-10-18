import { http } from './http';
import type {
  ApiResponse,
  PortalProfile,
  PortalProfileUpdate,
} from '../types/portal';

/**
 * 4.5A.4-T4.2 — Portal profile service.
 *
 * 4.5A is a stub: GET returns { customerId, note } and PUT is a no-op
 * (audit-log only).  4.5B replaces with full profile table.
 */

export const portalProfileService = {
  async get(): Promise<PortalProfile> {
    const r = await http.get<ApiResponse<PortalProfile>>('/customer/profile');
    return r.data.data;
  },

  async update(body: PortalProfileUpdate): Promise<void> {
    await http.put<ApiResponse<void>>('/customer/profile', body);
  },
};
