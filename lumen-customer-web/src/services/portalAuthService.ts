import { http } from './http';
import { useAuthStore } from '../store/authStore';
import type {
  ApiResponse,
  PortalLoginRequest,
  PortalLoginResponse,
  PortalMeResponse,
} from '../types/portal';

/**
 * 4.5A.4-T4.2 — Portal auth service.
 *
 * - login: obtains initial token, returns choices if multi-customer.
 * - selectCustomer: chosen on the SelectCustomer page; per T3.5 fix the FE
 *   echoes userId (from the login response) in the request body, because
 *   CustomerContextFilter does not run on /auth/portal/select-customer.
 * - me: refreshes display info (username / realName / activeCustomerName).
 *
 * Sibling refresh-token endpoint is consumed by http.ts's response interceptor;
 * we don't expose it here.
 */

export const portalAuthService = {
  async login(req: PortalLoginRequest): Promise<PortalLoginResponse> {
    const r = await http.post<ApiResponse<PortalLoginResponse>>(
      '/auth/portal/login',
      req
    );
    return r.data.data;
  },

  async selectCustomer(customerId: number): Promise<PortalLoginResponse> {
    const userId = useAuthStore.getState().userId;
    if (userId == null) {
      throw new Error('selectCustomer called without an authenticated userId');
    }
    const r = await http.post<ApiResponse<PortalLoginResponse>>(
      '/auth/portal/select-customer',
      { userId, customerId }
    );
    return r.data.data;
  },

  async me(): Promise<PortalMeResponse> {
    const r = await http.get<ApiResponse<PortalMeResponse>>('/auth/portal/me');
    return r.data.data;
  },
};
