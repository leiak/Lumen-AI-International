/**
 * 4.5C-2.1 — portalBillingService 的参数映射单测 (缺口 #9 的 service 侧)。
 *
 * 复审发现: `page` / `size` 怎么映射到 axios 的 params, **零断言** ——
 * 把 size 删掉, 全部用例照样绿。所以这里逐个方法断言 size/page **到达 wire**。
 *
 * 用 `mock.calls.at(-1)` 而不是 `mock.calls[0]` —— mock 在用例之间累积,
 * `[0]` 会拿到上一个用例的调用 (Task 4 踩过这个坑)。
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { http } from '../http';
import { portalBillingService } from '../portalBillingService';

vi.mock('../http');

/**
 * 取最后一次 http.get 的 axios params。
 *
 * 用 `calls[calls.length - 1]` 而不是 `calls.at(-1)` —— 本仓 tsconfig 的
 * target 是 ES2020, `Array.prototype.at` 是 ES2022 才有的, tsc 会直接报错。
 */
function lastParams(): Record<string, unknown> | undefined {
  const calls = vi.mocked(http.get).mock.calls;
  const last = calls[calls.length - 1];
  return last?.[1]?.params as Record<string, unknown> | undefined;
}

describe('portalBillingService', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('listRecons 把 page/size 原样送到 wire', async () => {
    const mockPage: any = { records: [], total: 0, page: 2, size: 50 };
    (http.get as any).mockResolvedValue({ data: { code: 0, data: mockPage } });

    const r = await portalBillingService.listRecons(undefined, 2, 50);

    expect(r).toEqual(mockPage);
    expect(lastParams()).toMatchObject({ page: 2, size: 50 });
  });

  it('listInvoices 把 page/size 原样送到 wire', async () => {
    const mockPage: any = { records: [], total: 0, page: 3, size: 10 };
    (http.get as any).mockResolvedValue({ data: { code: 0, data: mockPage } });

    const r = await portalBillingService.listInvoices(undefined, 3, 10);

    expect(r).toEqual(mockPage);
    expect(lastParams()).toMatchObject({ page: 3, size: 10 });
  });

  it('listPayments 把 page/size 原样送到 wire', async () => {
    const mockPage: any = { records: [], total: 0, page: 2, size: 50 };
    (http.get as any).mockResolvedValue({ data: { code: 0, data: mockPage } });

    const r = await portalBillingService.listPayments(undefined, 2, 50);

    expect(r).toEqual(mockPage);
    expect(lastParams()).toMatchObject({ page: 2, size: 50 });
  });

  it('listAdjustments 把 page/size 原样送到 wire', async () => {
    const mockPage: any = { records: [], total: 0, page: 4, size: 100 };
    (http.get as any).mockResolvedValue({ data: { code: 0, data: mockPage } });

    const r = await portalBillingService.listAdjustments(4, 100);

    expect(r).toEqual(mockPage);
    expect(lastParams()).toMatchObject({ page: 4, size: 100 });
  });

  it('listRecons 带期间筛选时, period 也原样送到 wire', async () => {
    const mockPage: any = { records: [], total: 0, page: 1, size: 20 };
    (http.get as any).mockResolvedValue({ data: { code: 0, data: mockPage } });

    await portalBillingService.listRecons('2026-08', 1, 20);

    expect(lastParams()).toMatchObject({ period: '2026-08', page: 1, size: 20 });
  });
});
