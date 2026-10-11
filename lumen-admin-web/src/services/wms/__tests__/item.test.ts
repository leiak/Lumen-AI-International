/**
 * services/wms/item.ts — smoke tests (PR5-T5.5).
 *
 * Minimal coverage — locks the `list()` verb + path so a future refactor of
 * the masterdata controller can't silently break the item picker.
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
import { itemService } from '@/services/wms/item';

describe('itemService', () => {
  it('list() GETs /wms/items with keyword filter', async () => {
    vi.mocked(request.get).mockResolvedValueOnce({ code: 0, data: { records: [], total: 0 } });
    await itemService.list({ pageNum: 1, pageSize: 20, keyword: 'SKU-001' });

    const [path, opts] = vi.mocked(request.get).mock.calls[0];
    expect(path).toBe('/api/v1/wms/items');
    expect(opts?.params).toEqual({ pageNum: 1, pageSize: 20, keyword: 'SKU-001' });
  });
});
