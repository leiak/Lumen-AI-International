/**
 * services/dispatch/exception.ts — smoke tests (Iteration 4.1 PR6).
 *
 * Locks the two endpoints the Exception detail page calls:
 *
 *   1. `investigate()` — POST {handler} to /{id}/investigate
 *                        (OPEN -> INVESTIGATING).
 *   2. `close()`       — POST to /{id}/close (RESOLVED -> CLOSED).
 *
 * `resolve()` / `reopen()` / `create()` follow the same body / path
 * shape and are covered transitively via the round-trip tests in the
 * backend IT suite; we don't re-test every variant here.
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
import { deliveryExceptionService } from '@/services/dispatch/exception';

describe('deliveryExceptionService', () => {
  it('investigate() POSTs {handler} to /{id}/investigate', async () => {
    vi.mocked(request.post).mockResolvedValueOnce({
      code: 0,
      data: { id: 13, status: 'INVESTIGATING', handler: '李四' },
    });
    await deliveryExceptionService.investigate(13, { handler: '李四' });

    expect(request.post).toHaveBeenCalledWith(
      '/api/v1/dispatch/exceptions/13/investigate',
      { handler: '李四' },
    );
  });

  it('close() POSTs to /{id}/close (no body)', async () => {
    vi.mocked(request.post).mockResolvedValueOnce({
      code: 0,
      data: { id: 13, status: 'CLOSED' },
    });
    await deliveryExceptionService.close(13);

    expect(request.post).toHaveBeenCalledWith(
      '/api/v1/dispatch/exceptions/13/close',
      {},
    );
  });
});
