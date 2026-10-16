/**
 * cs slaConfig service smoke (Iteration 4.2 PR6).
 *
 * Re-locks the SLA config CRUD wire-shape since `useSlaConfigs` depends
 * on `slaConfigService.list` and the PR6 hooks test depends on the
 * contract being stable.
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
    ApiEnvelope: class {},
    ApiError: class extends Error {},
  };
});

import { request } from '@/services/request';
import { slaConfigService } from '@/services/cs/slaConfig';

describe('slaConfigService', () => {
  it('list() GETs /cs/sla-configs with severity/level filter params', async () => {
    vi.mocked(request.get).mockResolvedValueOnce({
      code: 0,
      data: { items: [], total: 0 },
    });

    await slaConfigService.list({ severity: 'HIGH', level: 'L1', page: 1, size: 20 });

    const [path, opts] = vi.mocked(request.get).mock.calls[0];
    expect(path).toBe('/api/v1/cs/sla-configs');
    expect(opts?.params).toEqual({
      severity: 'HIGH',
      level: 'L1',
      page: 1,
      size: 20,
    });
  });
});