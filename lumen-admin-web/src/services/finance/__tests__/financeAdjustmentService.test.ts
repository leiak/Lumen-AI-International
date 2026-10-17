/**
 * services/finance/financeAdjustmentService.ts — smoke (Iteration 4.3.2 PR5).
 *
 * Locks the wire-shape for the two most-distinctive endpoints:
 *   1. `submitAdjustment()` — POST to /{id}/submit; PENDING_L1 transition.
 *   2. `approveAdjustmentL1()` — POST to /{id}/l1-approve with optional
 *      `comment` body param.
 *
 * See `lumen-parent/lumen-finance/.../controller/BillingAdjustmentController.java`.
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
import { financeAdjustmentService } from '@/services/finance/financeAdjustmentService';

describe('financeAdjustmentService', () => {
  it('submitAdjustment() POSTs to /{id}/submit and returns DRAFT → PENDING_L1', async () => {
    vi.mocked(request.post).mockResolvedValueOnce({
      id: 7,
      sourceInvoiceId: 11,
      adjustmentType: 'CREDIT',
      amount: 100.5,
      status: 'PENDING_L1',
      l1ApproverId: null,
      l2ApproverId: null,
    });

    const res = await financeAdjustmentService.submitAdjustment(7);

    expect(request.post).toHaveBeenCalledWith(
      '/api/v1/finance/adjustments/7/submit',
      {},
    );
    expect(res.status).toBe('PENDING_L1');
    expect(res.adjustmentType).toBe('CREDIT');
  });

  it('approveAdjustmentL1() POSTs { comment } in body to /l1-approve (PENDING_L1 → PENDING_L2)', async () => {
    vi.mocked(request.post).mockResolvedValueOnce({
      id: 7,
      status: 'PENDING_L2',
      l1ApproverId: 42,
      comment: '已核对原始单据',
    });

    const res = await financeAdjustmentService.approveAdjustmentL1(7, '已核对原始单据');

    expect(request.post).toHaveBeenCalledWith(
      '/api/v1/finance/adjustments/7/l1-approve',
      { comment: '已核对原始单据' },
    );
    expect(res.status).toBe('PENDING_L2');
    expect(res.l1ApproverId).toBe(42);
  });
});
