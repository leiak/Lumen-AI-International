/**
 * 4.5B.3-T3.4 — OrderDetail page tests.
 */

import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import OrderDetail from '../OrderDetail';
import { portalOrderService } from '../../../services/portalOrderService';
import type { PortalOrderDetail } from '../../../types/order';

vi.mock('../../../services/portalOrderService');
const mockDetail = vi.mocked(portalOrderService.detail);
const mockCancel = vi.mocked(portalOrderService.cancel);

const navigateMock = vi.fn();
vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof import('react-router-dom')>('react-router-dom');
  return { ...actual, useNavigate: () => navigateMock };
});

function baseDetail(over: Partial<PortalOrderDetail> = {}): PortalOrderDetail {
  return {
    doId: 42,
    doNo: 'PO-0042',
    status: 'PENDING_DISPATCH',
    originAddress: '上海市 A',
    destAddress: '北京市 B',
    createdAt: '2026-10-20T10:00:00',
    signedAt: null,
    plannedPickupWindowStart: '2026-10-21T14:00:00',
    plannedPickupWindowEnd: '2026-10-21T18:00:00',
    canCancel: true,
    timeline: [
      {
        status: 'CREATED',
        eventName: '客户下单',
        operator: null,
        occurredAt: '2026-10-20T10:00:00',
        remark: null,
      },
    ],
    ...over,
  };
}

function renderDetail(doId = '42') {
  return render(
    <MemoryRouter initialEntries={[`/orders/${doId}`]}>
      <Routes>
        <Route path="/orders/:doId" element={<OrderDetail />} />
      </Routes>
    </MemoryRouter>
  );
}

describe('OrderDetail page', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('渲染基本信息与流转记录', async () => {
    mockDetail.mockResolvedValue(baseDetail());

    renderDetail();

    await waitFor(() => expect(screen.getByText('PO-0042')).toBeInTheDocument());
    expect(mockDetail).toHaveBeenCalledWith(42);
    expect(screen.getByText('上海市 A')).toBeInTheDocument();
    expect(screen.getByText('北京市 B')).toBeInTheDocument();
    expect(screen.getByText('2026-10-21 14:00 ~ 2026-10-21 18:00')).toBeInTheDocument();
    expect(screen.getByText('客户下单')).toBeInTheDocument();
  });

  it('timeline 为空时展示空状态', async () => {
    mockDetail.mockResolvedValue(baseDetail({ timeline: [] }));

    renderDetail();

    await waitFor(() => expect(screen.getByText('暂无流转记录')).toBeInTheDocument());
  });

  it('canCancel=false 时取消按钮禁用', async () => {
    mockDetail.mockResolvedValue(baseDetail({ canCancel: false }));

    renderDetail();

    const btn = await screen.findByRole('button', { name: '当前状态不可取消' });
    expect(btn).toBeDisabled();
  });

  it('取消订单弹窗收集原因后调 cancel 并刷新详情', async () => {
    mockDetail.mockResolvedValue(baseDetail());
    mockCancel.mockResolvedValue(baseDetail({ status: 'CANCELLED', canCancel: false }));

    renderDetail();

    await userEvent.click(await screen.findByRole('button', { name: '取消订单' }));

    const reasonBox = await screen.findByPlaceholderText('请填写取消原因');
    await userEvent.type(reasonBox, '客户改主意');
    await userEvent.click(screen.getByRole('button', { name: '确 认' }));

    await waitFor(() => {
      expect(mockCancel).toHaveBeenCalledWith(42, { reason: '客户改主意' });
    });
    // 状态标签同时出现在页头和基本信息表里
    await waitFor(() => {
      expect(screen.getAllByText('已取消').length).toBeGreaterThanOrEqual(1);
    });
  });

  it('取消失败时展示错误提示', async () => {
    mockDetail.mockResolvedValue(baseDetail());
    mockCancel.mockRejectedValue({ response: { data: { message: '订单当前不可取消' } } });

    renderDetail();

    await userEvent.click(await screen.findByRole('button', { name: '取消订单' }));
    await userEvent.type(await screen.findByPlaceholderText('请填写取消原因'), 'x');
    await userEvent.click(screen.getByRole('button', { name: '确 认' }));

    await waitFor(() => {
      expect(screen.getByText('订单当前不可取消')).toBeInTheDocument();
    });
    expect(mockCancel).toHaveBeenCalledTimes(1);
  });

  it('无效 doId 展示 404', async () => {
    renderDetail('abc');

    expect(await screen.findByText('无效的订单 ID')).toBeInTheDocument();
    expect(mockDetail).not.toHaveBeenCalled();
  });
});