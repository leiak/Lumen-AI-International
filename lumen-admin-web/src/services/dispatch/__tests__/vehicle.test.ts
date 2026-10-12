/**
 * services/dispatch/vehicle.ts — smoke tests (Iteration 4.1 PR6).
 *
 * Locks the HTTP surface (verb + path + body shape) for the three
 * most distinctive vehicle endpoints:
 *
 *   1. `create()` — POST with the request body shape (plateNo + dims).
 *   2. `list()`   — GET with filters passed through to query params.
 *   3. `freeze()` — POST to the /freeze endpoint (state machine transition).
 *
 * We mock `@/services/request` so the service module forwards to a
 * vi.fn() we can assert on.
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
import { vehicleService } from '@/services/dispatch/vehicle';

describe('vehicleService', () => {
  it('list() GETs /wms/vehicles with the filter params', async () => {
    vi.mocked(request.get).mockResolvedValueOnce({
      code: 0,
      data: { records: [], total: 0 },
    });
    await vehicleService.list({ current: 1, size: 20, status: 'IDLE', type: 'SMALL' });

    const [path, opts] = vi.mocked(request.get).mock.calls[0];
    expect(path).toBe('/api/v1/wms/vehicles');
    expect(opts?.params).toEqual({ current: 1, size: 20, status: 'IDLE', type: 'SMALL' });
  });

  it('create() POSTs the request body to /wms/vehicles', async () => {
    vi.mocked(request.post).mockResolvedValueOnce({
      code: 0,
      data: { id: 1, plateNo: '京A12345', status: 'IDLE' },
    });
    await vehicleService.create({
      plateNo: '京A12345',
      type: 'SMALL',
      maxLoadKg: 1500,
      maxVolumeM3: 8,
      remark: 'EV truck',
    });

    expect(request.post).toHaveBeenCalledWith('/api/v1/wms/vehicles', {
      plateNo: '京A12345',
      type: 'SMALL',
      maxLoadKg: 1500,
      maxVolumeM3: 8,
      remark: 'EV truck',
    });
  });

  it('freeze() POSTs to /{id}/freeze (state-machine transition)', async () => {
    vi.mocked(request.post).mockResolvedValueOnce({
      code: 0,
      data: { id: 1, plateNo: '京A12345', status: 'OUT' },
    });
    await vehicleService.freeze(1);

    expect(request.post).toHaveBeenCalledWith(
      '/api/v1/wms/vehicles/1/freeze',
      {},
    );
  });
});
