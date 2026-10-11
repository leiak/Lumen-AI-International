/**
 * services/wms/adjust.ts — smoke tests (PR5-T5.4).
 *
 * Same shape as the inbound suite — verifies HTTP verb + path + body for
 * the adjust-specific state machine endpoints (reject needs a body,
 * execute does not).
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
import { adjustNoteService } from '@/services/wms/adjust';

describe('adjustNoteService', () => {
  it('list() GETs /wms/inventory-adjusts with filters', async () => {
    vi.mocked(request.get).mockResolvedValueOnce({ code: 0, data: { records: [], total: 0 } });
    await adjustNoteService.list({ pageNum: 1, pageSize: 20, status: 'DRAFT' });

    const [path, opts] = vi.mocked(request.get).mock.calls[0];
    expect(path).toBe('/api/v1/wms/inventory-adjusts');
    expect(opts?.params).toEqual({ pageNum: 1, pageSize: 20, status: 'DRAFT' });
  });

  it('reject() POSTs the reason body to /{id}/reject', async () => {
    vi.mocked(request.post).mockResolvedValueOnce({ code: 0, data: { id: 3 } });
    await adjustNoteService.reject(3, { reason: 'unit cost out of range' });

    expect(request.post).toHaveBeenCalledWith(
      '/api/v1/wms/inventory-adjusts/3/reject',
      { reason: 'unit cost out of range' },
    );
  });

  it('execute() POSTs an empty body to /{id}/execute', async () => {
    vi.mocked(request.post).mockResolvedValueOnce({ code: 0, data: { id: 3 } });
    await adjustNoteService.execute(3);

    expect(request.post).toHaveBeenCalledWith(
      '/api/v1/wms/inventory-adjusts/3/execute',
      {},
    );
  });
});