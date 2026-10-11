/**
 * services/wms/inventory.ts — smoke tests (PR5-T5.4).
 *
 * Inventory is a read-only surface (no POST/PUT/DELETE). Verifies the
 * list/summary paths and that the BE-required warehouseId OR itemId filter
 * is forwarded verbatim.
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
import { inventoryService } from '@/services/wms/inventory';

describe('inventoryService', () => {
  it('list() forwards warehouseId OR itemId filter to /wms/inventories', async () => {
    vi.mocked(request.get).mockResolvedValueOnce({ code: 0, data: { records: [], total: 0 } });
    await inventoryService.list({ warehouseId: 2, pageNum: 1, pageSize: 10 });

    const [path, opts] = vi.mocked(request.get).mock.calls[0];
    expect(path).toBe('/api/v1/wms/inventories');
    expect(opts?.params).toEqual({ warehouseId: 2, pageNum: 1, pageSize: 10 });
  });

  it('summary() GETs the placeholder summary endpoint', async () => {
    vi.mocked(request.get).mockResolvedValueOnce({ code: 0, data: {} });
    await inventoryService.summary();

    expect(request.get).toHaveBeenCalledWith('/api/v1/wms/inventories/summary');
  });
});