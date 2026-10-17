/**
 * services/finance/financeRuleService.ts — smoke (Iteration 4.3.1 PR4).
 *
 * Locks the wire-shape for the two most-distinctive endpoints:
 *   1. `list()` — GET with pageNum/pageSize + businessType/status filters.
 *   2. `activate()` — POST to /{id}/activate; no body.
 *
 * See `lumen-parent/lumen-finance/.../controller/BillingRuleController.java`.
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
import { financeRuleService } from '@/services/finance/financeRuleService';

describe('financeRuleService', () => {
  it('list() GETs /finance/rules with pageNum/pageSize + filter params', async () => {
    // Response interceptor unwraps `R<T>` envelope in real code; mock
    // returns the inner array directly.
    vi.mocked(request.get).mockResolvedValueOnce([
      {
        id: 11,
        businessType: 'LAST_MILE',
        direction: 'RECEIVABLE',
        counterpartyType: 'CUSTOMER',
        status: 'ACTIVE',
        priority: 100,
      },
    ]);

    const res = await financeRuleService.list({
      businessType: 'LAST_MILE',
      status: 'ACTIVE',
      pageNum: 1,
      pageSize: 20,
    });

    const [path, opts] = vi.mocked(request.get).mock.calls[0];
    expect(path).toBe('/api/v1/finance/rules');
    expect(opts?.params).toEqual({
      businessType: 'LAST_MILE',
      status: 'ACTIVE',
      pageNum: 1,
      pageSize: 20,
    });
    expect(res.records).toHaveLength(1);
    expect(res.total).toBe(1);
  });

  it('activate() POSTs to /{id}/activate and returns the updated rule', async () => {
    vi.mocked(request.post).mockResolvedValueOnce({
      id: 11,
      businessType: 'LAST_MILE',
      direction: 'RECEIVABLE',
      counterpartyType: 'CUSTOMER',
      status: 'ACTIVE',
    });
    const updated = await financeRuleService.activate(11);

    expect(request.post).toHaveBeenCalledWith(
      '/api/v1/finance/rules/11/activate',
      {},
    );
    expect(updated.status).toBe('ACTIVE');
    expect(updated.id).toBe(11);
  });
});