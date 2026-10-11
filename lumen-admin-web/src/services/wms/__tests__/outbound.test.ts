/**
 * services/wms/outbound.ts — smoke tests (PR5-T5.5).
 *
 * Mirrors the inbound suite — locks the HTTP surface (verb + path + body
 * shape) for the three most distinctive outbound endpoints. `list` + `get`
 * are generic; `ship` is the one that's different from inbound because it
 * carries a body (carrier + trackingNo) and runs the SHIPPED transition.
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
import { outboundNoteService } from '@/services/wms/outbound';

describe('outboundNoteService', () => {
  it('list() GETs /wms/outbound-notes with the query params', async () => {
    vi.mocked(request.get).mockResolvedValueOnce({ code: 0, data: { records: [], total: 0 } });
    await outboundNoteService.list({ pageNum: 1, pageSize: 20, keyword: 'O-1' });

    const [path, opts] = vi.mocked(request.get).mock.calls[0];
    expect(path).toBe('/api/v1/wms/outbound-notes');
    expect(opts?.params).toEqual({ pageNum: 1, pageSize: 20, keyword: 'O-1' });
  });

  it('get() GETs /wms/outbound-notes/{id}', async () => {
    vi.mocked(request.get).mockResolvedValueOnce({ code: 0, data: { id: 9 } });
    await outboundNoteService.get(9);

    expect(request.get).toHaveBeenCalledWith('/api/v1/wms/outbound-notes/9');
  });

  it('ship() POSTs carrier+trackingNo to /{id}/ship', async () => {
    vi.mocked(request.post).mockResolvedValueOnce({ code: 0, data: { id: 9 } });
    await outboundNoteService.ship(9, { carrier: 'SF Express', trackingNo: 'SF1234567890' });

    expect(request.post).toHaveBeenCalledWith(
      '/api/v1/wms/outbound-notes/9/ship',
      { carrier: 'SF Express', trackingNo: 'SF1234567890' },
    );
  });
});
