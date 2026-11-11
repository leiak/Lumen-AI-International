/**
 * 4.6 — portalFreightOrderService 单元测试。
 *
 * 写法照抄既有的 `src/services/__tests__/portalOrderService.test.ts`:
 * `vi.mock('../http')` 把 axios 实例整个换掉, 直接断言
 * 「调了哪个 URL / 传了什么 payload / 有没有把 ApiResponse 解包成 data」。
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { http } from '../http';
import { portalFreightOrderService } from '../portalFreightOrderService';

vi.mock('../http');

/** 后端 `PageResult<T>` 的形状 —— records / total / pageNum / pageSize。 */
const PAGE = { records: [], total: 0, pageNum: 1, pageSize: 10 };

describe('portalFreightOrderService', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('list GETs /customer/freight-order with query params and unwraps ApiResponse.data', async () => {
    (http.get as any).mockResolvedValue({ data: { code: 0, data: PAGE } });

    const r = await portalFreightOrderService.list({
      status: 'CONFIRMED',
      current: 2,
      size: 20,
    });

    expect(r).toEqual(PAGE);
    expect(http.get).toHaveBeenCalledWith('/customer/freight-order', {
      params: { status: 'CONFIRMED', current: 2, size: 20 },
    });
  });

  it('detail GETs /customer/freight-order/{id} and unwraps ApiResponse.data', async () => {
    (http.get as any).mockResolvedValue({ data: { code: 0, data: { id: 123 } } });

    const r = await portalFreightOrderService.detail(123);

    expect(r).toEqual({ id: 123 });
    expect(http.get).toHaveBeenCalledWith('/customer/freight-order/123');
  });

  it('create POSTs to /customer/freight-order and the payload carries no customerId', async () => {
    (http.post as any).mockResolvedValue({ data: { code: 0, data: { id: 7 } } });

    const req = {
      businessType: 'AIR',
      tradeDirection: 'EXPORT',
      incoterms: 'FOB',
      serviceType: 'D2D',
      cargo: [],
      serviceItems: [],
    };

    const r = await portalFreightOrderService.create(req);

    expect(r).toEqual({ id: 7 });
    expect(http.post).toHaveBeenCalledWith('/customer/freight-order', req);
    // 客户归属由后端从 JWT 注入 —— 客户端**不能**指定 customerId。
    expect((http.post as any).mock.calls[0][1]).not.toHaveProperty('customerId');
  });

  it('confirm POSTs to /customer/freight-order/{id}/confirm', async () => {
    (http.post as any).mockResolvedValue({
      data: { code: 0, data: { orderStatus: 'CONFIRMED' } },
    });

    const r = await portalFreightOrderService.confirm(123);

    expect(r).toEqual({ orderStatus: 'CONFIRMED' });
    expect(http.post).toHaveBeenCalledWith('/customer/freight-order/123/confirm');
  });

  it('cancel POSTs to /customer/freight-order/{id}/cancel with the reason', async () => {
    (http.post as any).mockResolvedValue({
      data: { code: 0, data: { orderStatus: 'CANCELLED' } },
    });

    const r = await portalFreightOrderService.cancel(123, '客户改主意');

    expect(r).toEqual({ orderStatus: 'CANCELLED' });
    expect(http.post).toHaveBeenCalledWith('/customer/freight-order/123/cancel', {
      reason: '客户改主意',
    });
  });
});
