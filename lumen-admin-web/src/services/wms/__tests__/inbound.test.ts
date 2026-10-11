/**
 * services/wms/inbound.ts — smoke tests (PR5-T5.4).
 *
 * The point of these tests is to lock the HTTP surface (verb + path + body
 * shape) so a future refactor of the controller / path can't silently
 * break Detail / List pages. We mock the `request` axios instance as a
 * plain object whose methods return `Promise.resolve(...)`; the service
 * modules just forward to those mocks.
 */

import { describe, it, expect, vi } from 'vitest';

// Mock the axios instance before importing the service module so its
// namespace methods pick up the mocks via `request.get<T>`, etc.
vi.mock('@/services/request', () => {
  const get = vi.fn();
  const post = vi.fn();
  const put = vi.fn();
  const del = vi.fn();
  const request = { get, post, put, delete: del };
  return {
    request,
    default: request,
    // Re-export the type so callers keep compiling — at runtime these are
    // unused by the test file.
    PageResult: class {},
    R: class {},
    ApiError: class extends Error {},
  };
});

import { request } from '@/services/request';
import { inboundNoteService } from '@/services/wms/inbound';

describe('inboundNoteService', () => {
  it('list() GETs /wms/inbound-notes with the query params', async () => {
    vi.mocked(request.get).mockResolvedValueOnce({ code: 0, data: { records: [], total: 0 } });
    await inboundNoteService.list({ pageNum: 1, pageSize: 20, status: 'DRAFT' });

    expect(request.get).toHaveBeenCalledTimes(1);
    const [path, opts] = vi.mocked(request.get).mock.calls[0];
    expect(path).toBe('/api/v1/wms/inbound-notes');
    expect(opts?.params).toEqual({ pageNum: 1, pageSize: 20, status: 'DRAFT' });
  });

  it('get() GETs /wms/inbound-notes/{id}', async () => {
    vi.mocked(request.get).mockResolvedValueOnce({ code: 0, data: { id: 7 } });
    await inboundNoteService.get(7);

    expect(request.get).toHaveBeenCalledWith('/api/v1/wms/inbound-notes/7');
  });

  it('submit() POSTs an empty body to /{id}/submit', async () => {
    vi.mocked(request.post).mockResolvedValueOnce({ code: 0, data: { id: 7 } });
    await inboundNoteService.submit(7);

    expect(request.post).toHaveBeenCalledWith('/api/v1/wms/inbound-notes/7/submit', {});
  });

  it('receiveLine() POSTs qty to /{id}/lines/{lid}/receive', async () => {
    vi.mocked(request.post).mockResolvedValueOnce({ code: 0, data: { id: 11 } });
    await inboundNoteService.receiveLine(7, 11, { qty: 5 });

    expect(request.post).toHaveBeenCalledWith(
      '/api/v1/wms/inbound-notes/7/lines/11/receive',
      { qty: 5 },
    );
  });

  it('create() POSTs the request body to /wms/inbound-notes', async () => {
    vi.mocked(request.post).mockResolvedValueOnce({ code: 0, data: { id: 7 } });
    await inboundNoteService.create({
      no: 'IN-001',
      customerId: 1,
      warehouseId: 2,
      lines: [{ itemId: 10, qty: 5, putawayLocationId: 100 }],
    });

    expect(request.post).toHaveBeenCalledWith('/api/v1/wms/inbound-notes', {
      no: 'IN-001',
      customerId: 1,
      warehouseId: 2,
      lines: [{ itemId: 10, qty: 5, putawayLocationId: 100 }],
    });
  });

  it('addLine() POSTs a single line to /{id}/lines', async () => {
    vi.mocked(request.post).mockResolvedValueOnce({ code: 0, data: { id: 12 } });
    await inboundNoteService.addLine(7, { itemId: 11, qty: 3 });

    expect(request.post).toHaveBeenCalledWith('/api/v1/wms/inbound-notes/7/lines', {
      itemId: 11,
      qty: 3,
    });
  });

  it('removeLine() DELETEs /{id}/lines/{lid}', async () => {
    vi.mocked(request.delete).mockResolvedValueOnce({ code: 0, data: null });
    await inboundNoteService.removeLine(7, 11);

    expect(request.delete).toHaveBeenCalledWith('/api/v1/wms/inbound-notes/7/lines/11');
  });
});