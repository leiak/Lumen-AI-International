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

  it('listAdjustments() 把 disputed=false 原样送到 wire —— 不能被吞成 undefined', async () => {
    // 4.5C-2.1 (缺口 #13) — 「无异议」筛选靠的是 disputed=false 真的到了后端。
    // 写成 `params.disputed || undefined` 会把 false 吞成 undefined, axios
    // 跳过该参数, 后端于是**不过滤** —— 列表里照样出现有异议的单子,
    // 而用户以为自己筛过了。这个故障没有任何报错, 只能靠这条用例拦。
    vi.mocked(request.get).mockResolvedValueOnce([]);

    await financeAdjustmentService.listAdjustments({ disputed: false, page: 1, size: 20 });

    // 用 .at(-1) 而不是 [0] —— request.get 的 mock 在用例之间不清空,
    // 取第 0 次调用会拿到上一条用例的入参。
    const call = vi.mocked(request.get).mock.calls.at(-1);
    expect(call).toBeDefined();
    const params = call?.[1]?.params as Record<string, unknown> | undefined;
    expect(params?.disputed).toBe(false);
  });

  it('listAdjustments() 把 disputed=true 送到 wire', async () => {
    vi.mocked(request.get).mockResolvedValueOnce([]);

    await financeAdjustmentService.listAdjustments({ disputed: true, page: 1, size: 20 });

    const call = vi.mocked(request.get).mock.calls.at(-1);
    expect(call).toBeDefined();
    const params = call?.[1]?.params as Record<string, unknown> | undefined;
    expect(params?.disputed).toBe(true);
  });
});
