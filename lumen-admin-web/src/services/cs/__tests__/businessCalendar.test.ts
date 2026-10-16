/**
 * cs businessCalendar service smoke (Iteration 4.2 PR6).
 *
 * Single smoke test to lock the wire-shape — the hook tests already
 * cover the more nuanced flow.
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
import { businessCalendarService } from '@/services/cs/businessCalendar';

describe('businessCalendarService', () => {
  it('list() GETs /cs/calendar with no params', async () => {
    vi.mocked(request.get).mockResolvedValueOnce({
      code: 0,
      data: { items: [], total: 0 },
    });

    await businessCalendarService.list();

    const [path, opts] = vi.mocked(request.get).mock.calls[0];
    expect(path).toBe('/api/v1/cs/calendar');
    expect(opts).toBeUndefined();
  });
});