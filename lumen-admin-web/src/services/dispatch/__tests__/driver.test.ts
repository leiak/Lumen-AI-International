/**
 * services/dispatch/driver.ts — smoke tests (Iteration 4.1 PR6).
 *
 * Covers the two endpoints a dispatcher uses from the Driver list:
 *
 *   1. `create()` — POST with the request body shape (name + phone + licenseNo).
 *   2. `suspend()` — POST to the /suspend endpoint (ACTIVE -> SUSPENDED).
 *
 * `resume()` is intentionally not tested here — its verb/path/body
 * shape is identical to `suspend()` minus the perm gate; PR5 hooks
 * already cover resume, and `freeze()` tests in `vehicle.test.ts` lock
 * the analogous transition verb.
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
import { driverService } from '@/services/dispatch/driver';

describe('driverService', () => {
  it('create() POSTs the request body to /wms/drivers', async () => {
    vi.mocked(request.post).mockResolvedValueOnce({
      code: 0,
      data: { id: 5, name: '张三', status: 'ACTIVE' },
    });
    await driverService.create({
      name: '张三',
      phone: '13800138000',
      licenseNo: 'B1234567',
    });

    expect(request.post).toHaveBeenCalledWith('/api/v1/wms/drivers', {
      name: '张三',
      phone: '13800138000',
      licenseNo: 'B1234567',
    });
  });

  it('suspend() POSTs to /{id}/suspend (ACTIVE -> SUSPENDED)', async () => {
    vi.mocked(request.post).mockResolvedValueOnce({
      code: 0,
      data: { id: 5, name: '张三', status: 'SUSPENDED' },
    });
    await driverService.suspend(5);

    expect(request.post).toHaveBeenCalledWith(
      '/api/v1/wms/drivers/5/suspend',
      {},
    );
  });
});
