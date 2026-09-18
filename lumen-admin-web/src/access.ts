/**
 * Lumen Admin — permission sync helper.
 *
 * Loads the current user's perm codes from `/auth/me` (RbacUserDetails.perms)
 * and exposes a `canRead(code)` predicate. Returns an empty (deny-all) policy
 * on any error so a transient /me failure defaults to "no access" rather
 * than "all access".
 *
 * Usage:
 *   const { canRead, perms } = await getAccess();
 *   if (canRead('user:read')) { ... }
 */

import { request } from '@/services/request';

export interface AccessState {
  canRead: (permCode: string) => boolean;
  perms: string[];
}

/** Shape returned by GET /api/v1/auth/me — see RbacUserDetails.java */
interface MeResponse {
  userId: number;
  tenantId: number;
  roles: string[];
  perms: string[];
}

export async function getAccess(): Promise<AccessState> {
  try {
    // `request.get<T>` is typed as Promise<AxiosResponse<T>>, but our
    // response interceptor unwraps the R<T> envelope and returns `data`
    // directly. Cast through unknown so consumers see the unwrapped type.
    const me = (await request.get<unknown>('/auth/me')) as unknown as MeResponse | null;
    const perms = me?.perms ?? [];
    const set = new Set(perms);
    return {
      canRead: (code: string) => set.has(code),
      perms,
    };
  } catch {
    // Fail closed: deny everything if /me is unavailable.
    return {
      canRead: () => false,
      perms: [],
    };
  }
}
