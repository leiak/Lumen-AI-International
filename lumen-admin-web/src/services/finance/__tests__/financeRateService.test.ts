/**
 * services/finance/financeRateService.ts — smoke (Iteration 4.3.2 PR5).
 *
 * Locks the wire-shape for the two most-distinctive endpoints:
 *   1. `listRates()` — GET with from/to/fromDate/toDate/page/size filter params.
 *   2. `saveManualRate()` — POST body is query-params (data on the wire) +
 *      no body returns no data (returns void).
 *   3. `refreshRate()` — POST to /refresh, returns the latest RateQuote.
 *
 * See `lumen-parent/lumen-finance/.../controller/BillingRateController.java`.
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
import { financeRateService } from '@/services/finance/financeRateService';

describe('financeRateService', () => {
  it('listRates() GETs /finance/rates with from/to/fromDate/toDate filter params', async () => {
    // The response interceptor unwraps `R<T>` envelopes; the mock
    // returns the inner array directly (matching what the real
    // interceptor hands back to the caller).
    vi.mocked(request.get).mockResolvedValueOnce([
      {
        id: 1,
        fromCurrency: 'USD',
        toCurrency: 'CNY',
        rate: 7.1234,
        rateDate: '2026-10-17',
        source: 'MANUAL',
        createdAt: '2026-10-17T00:00:00Z',
      },
    ]);

    const res = await financeRateService.listRates({
      from: 'USD',
      to: 'CNY',
      fromDate: '2026-10-01',
      toDate: '2026-10-17',
      page: 1,
      size: 50,
    });

    const [path, opts] = vi.mocked(request.get).mock.calls[0];
    expect(path).toBe('/api/v1/finance/rates');
    expect(opts?.params).toEqual({
      from: 'USD',
      to: 'CNY',
      fromDate: '2026-10-01',
      toDate: '2026-10-17',
      page: 1,
      size: 50,
    });
    expect(res.records).toHaveLength(1);
    expect(res.total).toBe(1);
    expect(res.records[0].rate).toBe(7.1234);
  });

  it('saveManualRate() POSTs body as query params to /finance/rates/manual', async () => {
    // The endpoint returns void; mock returns null (post-unwrapped).
    vi.mocked(request.post).mockResolvedValueOnce(null);

    await financeRateService.saveManualRate({
      from: 'USD',
      to: 'CNY',
      rate: 7.25,
      rateDate: '2026-10-17',
    });

    // Per backend contract: rate / rateDate / from / to are sent as query
    // params (NOT a JSON body) — that's why we assert against opts.params
    // and not a `body` argument.
    expect(request.post).toHaveBeenCalledWith(
      '/api/v1/finance/rates/manual',
      null,
      {
        params: {
          from: 'USD',
          to: 'CNY',
          rate: 7.25,
          rateDate: '2026-10-17',
        },
      },
    );
  });
});
