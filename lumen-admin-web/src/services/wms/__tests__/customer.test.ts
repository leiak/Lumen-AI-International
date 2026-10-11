/**
 * services/wms/customer.ts — smoke tests (PR5-T5.5).
 *
 * Minimal coverage — locks the `list()` verb + path so a future refactor of
 * the masterdata controller can't silently break the customer picker.
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
import { customerService } from '@/services/wms/customer';

describe('customerService', () => {
  it('list() GETs /wms/customers with keyword filter', async () => {
    vi.mocked(request.get).mockResolvedValueOnce({ code: 0, data: { records: [], total: 0 } });
    await customerService.list({ pageNum: 1, pageSize: 20, keyword: 'ACME' });

    const [path, opts] = vi.mocked(request.get).mock.calls[0];
    expect(path).toBe('/api/v1/wms/customers');
    expect(opts?.params).toEqual({ pageNum: 1, pageSize: 20, keyword: 'ACME' });
  });
});
