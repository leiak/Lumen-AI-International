/**
 * services/wms/location.ts — smoke tests (PR5-T5.5).
 *
 * Verifies the non-paged `listByWarehouse` endpoint — it's the only masterdata
 * service that exposes an unpaged read alongside the standard list/get
 * pair, and it's the one the putaway/pick selects depend on.
 */

import { describe, it, expect, vi } from 'vitest';

vi.mock('@/services/request', () => {
  const get = vi.fn();
  const post = vi.fn();
  const put = vi.fn();
  const del = vi.fn();
  const request = { get, post, put, delete: del };
  return {
    request,
    default: request,
    PageResult: class {},
    R: class {},
    ApiError: class extends Error {},
  };
});

import { request } from '@/services/request';
import { locationService } from '@/services/wms/location';

describe('locationService', () => {
  it('listByWarehouse() GETs /wms/locations/by-warehouse/{warehouseId}', async () => {
    vi.mocked(request.get).mockResolvedValueOnce({ code: 0, data: [] });
    await locationService.listByWarehouse(7);

    expect(request.get).toHaveBeenCalledWith('/api/v1/wms/locations/by-warehouse/7');
  });
});
