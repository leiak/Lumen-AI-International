/**
 * services/finance/financeInvoiceService.ts — smoke (Iteration 4.3.1 PR4).
 *
 * Locks the wire-shape for the two most-distinctive endpoints:
 *   1. `list()` — GET with pageNum/pageSize filter params.
 *   2. `l1Reject()` — POST body must contain `comment` (backend reads
 *      `Map<String,String>` and looks up the "comment" key).
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
import { financeInvoiceService } from '@/services/finance/financeInvoiceService';

describe('financeInvoiceService', () => {
  it('list() GETs /finance/invoices with pageNum/pageSize params', async () => {
    // The response interceptor unwraps `R<T>` envelopes, so the mock
    // returns the inner array directly (matching what the real
    // interceptor hands back to the caller).
    vi.mocked(request.get).mockResolvedValueOnce([
      {
        id: 1,
        invoiceNo: 'AR-1-1700000000-1',
        direction: 'RECEIVABLE',
        status: 'PENDING_L1',
        netAmount: 1234.5,
        currency: 'CNY',
        createdAt: '2026-10-17T00:00:00Z',
      },
    ]);

    const res = await financeInvoiceService.list({
      direction: 'RECEIVABLE',
      status: 'PENDING_L1',
      pageNum: 2,
      pageSize: 50,
    });

    const [path, opts] = vi.mocked(request.get).mock.calls[0];
    expect(path).toBe('/api/v1/finance/invoices');
    expect(opts?.params).toEqual({
      direction: 'RECEIVABLE',
      status: 'PENDING_L1',
      pageNum: 2,
      pageSize: 50,
    });
    expect(res.records).toHaveLength(1);
    expect(res.total).toBe(1);
  });

  it('l1Reject() POSTs { comment } in body (not "reason")', async () => {
    vi.mocked(request.post).mockResolvedValueOnce({
      id: 1,
      invoiceNo: 'AR-1-1700000000-1',
      direction: 'RECEIVABLE',
      status: 'REJECTED',
    });
    await financeInvoiceService.l1Reject(1, '金额核对不符');

    expect(request.post).toHaveBeenCalledWith(
      '/api/v1/finance/invoices/1/l1-reject',
      { comment: '金额核对不符' },
    );
  });
});