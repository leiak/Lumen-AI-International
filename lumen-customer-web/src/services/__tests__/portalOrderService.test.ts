import { describe, it, expect, vi, beforeEach } from 'vitest';
import { http } from '../http';
import { portalOrderService } from '../portalOrderService';

vi.mock('../http');

describe('portalOrderService', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('preview POSTs to /customer/order/preview and unwraps ApiResponse.data', async () => {
    const mockResult: any = {
      amount: 123.45,
      source: 'RULE',
      ruleId: 99,
      overrideId: null,
      currency: 'CNY',
      breakdown: '基础 ¥100 + 距离 ¥23.45 = ¥123.45',
    };
    (http.post as any).mockResolvedValue({ data: { code: 0, data: mockResult } });

    const r = await portalOrderService.preview({
      warehouseId: 1,
      shipperAddress: 'A',
      consigneeAddress: 'B',
      totalWeightKg: 10,
      totalVolumeM3: 0.5,
      stopCount: 1,
      plannedPickupWindowStart: '2026-10-20T14:00:00',
      plannedPickupWindowEnd: null,
      remark: null,
    });

    expect(r).toEqual(mockResult);
    expect(http.post).toHaveBeenCalledWith(
      '/customer/order/preview',
      expect.objectContaining({ warehouseId: 1 })
    );
  });

  it('create POSTs to /customer/order and returns PortalOrderDetail', async () => {
    const mockDetail: any = {
      doId: 999,
      doNo: 'PO-ABCD1234',
      status: 'PENDING_DISPATCH',
      originAddress: 'A',
      destAddress: 'B',
      createdAt: '2026-10-20T10:00:00',
      signedAt: null,
      plannedPickupWindowStart: '2026-10-20T14:00:00',
      plannedPickupWindowEnd: '2026-10-20T18:00:00',
      canCancel: true,
      timeline: [],
    };
    (http.post as any).mockResolvedValue({ data: { code: 0, data: mockDetail } });

    const r = await portalOrderService.create({} as any);

    expect(r).toEqual(mockDetail);
    expect(http.post).toHaveBeenCalledWith('/customer/order', expect.any(Object));
  });

  it('list GETs /customer/order with query params', async () => {
    const mockList: any = { records: [], total: 0, page: 1, size: 20 };
    (http.get as any).mockResolvedValue({ data: { code: 0, data: mockList } });

    const r = await portalOrderService.list({ page: 1, size: 20, status: 'PENDING_DISPATCH' });

    expect(r).toEqual(mockList);
    expect(http.get).toHaveBeenCalledWith('/customer/order', {
      params: { page: 1, size: 20, status: 'PENDING_DISPATCH' },
    });
  });

  it('detail GETs /customer/order/{doId}', async () => {
    const mockDetail: any = { doId: 42, status: 'PENDING_DISPATCH', canCancel: true };
    (http.get as any).mockResolvedValue({ data: { code: 0, data: mockDetail } });

    const r = await portalOrderService.detail(42);

    expect(r.doId).toBe(42);
    expect(http.get).toHaveBeenCalledWith('/customer/order/42');
  });

  it('cancel POSTs to /customer/order/{doId}/cancel with reason', async () => {
    const mockResult: any = { doId: 42, status: 'CANCELLED' };
    (http.post as any).mockResolvedValue({ data: { code: 0, data: mockResult } });

    const r = await portalOrderService.cancel(42, { reason: '客户改主意' });

    expect(r.status).toBe('CANCELLED');
    expect(http.post).toHaveBeenCalledWith(
      '/customer/order/42/cancel',
      expect.objectContaining({ reason: '客户改主意' })
    );
  });
});