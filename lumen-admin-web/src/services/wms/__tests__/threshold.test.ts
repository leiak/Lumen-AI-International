/**
 * services/wms/threshold.ts — smoke tests (PR5-T5.5).
 *
 * Locks the read-only `get()` endpoint — `thresholdConfigService` only has
 * two verbs (GET + PUT) and the path is per-tenant (no id in the URL), so
 * a single get() assertion covers the unique surface.
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
import { thresholdConfigService } from '@/services/wms/threshold';

describe('thresholdConfigService', () => {
  it('get() GETs /wms/threshold (per-tenant, no id in path)', async () => {
    vi.mocked(request.get).mockResolvedValueOnce({ code: 0, data: { id: 1 } });
    await thresholdConfigService.get();

    expect(request.get).toHaveBeenCalledWith('/api/v1/wms/threshold');
  });
});
