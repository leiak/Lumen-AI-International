/**
 * 4.6 — 门户端货运委托单详情页测试。
 *
 * 重点守住两件事:
 *   1. 按钮显隐由后端返回的 `allowedNextStates` 决定, 前端不自己判断状态机。
 *   2. 取消原因不足 5 个字时**不发请求** (后端 `OrderConstants.REASON_MIN_LENGTH` = 5)。
 *
 * 用例超时统一放宽到 30s —— antd Modal / Table 在 jsdom 下渲染很重, 本机高负载时
 * 单条要跑 2~3s, 会撞上 vitest 默认的 5s 超时。
 */

import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import FreightOrderDetailPage from '../Detail';
import { portalFreightOrderService } from '../../../services/portalFreightOrderService';
import type { FreightOrderDetail } from '../../../types/freightOrder';

vi.mock('../../../services/portalFreightOrderService');
const mockDetail = vi.mocked(portalFreightOrderService.detail);
const mockConfirm = vi.mocked(portalFreightOrderService.confirm);
const mockCancel = vi.mocked(portalFreightOrderService.cancel);

function makeDetail(allowedNextStates: string[]): FreightOrderDetail {
  return {
    id: 123,
    orderNo: 'FO-2026-0001',
    customerId: 7,
    businessType: 'OCEAN_FCL',
    tradeDirection: 'EXPORT',
    incoterms: 'FOB',
    serviceType: 'D2D',
    originAddress: '上海',
    destAddress: '洛杉矶',
    totalQty: 10,
    totalWeightKg: 1200,
    totalVolumeCbm: 3.5,
    orderStatus: 'PENDING_CONFIRM',
    settlementCurrency: 'USD',
    etd: '2026-12-01',
    eta: '2026-12-20',
    createdAt: '2026-11-01T08:00:00',
    cargo: [],
    serviceItems: [],
    allowedNextStates,
  };
}

function renderDetail() {
  return render(
    <MemoryRouter initialEntries={['/freight-orders/123']}>
      <Routes>
        <Route
          path="/freight-orders/:id"
          element={<FreightOrderDetailPage />}
        />
      </Routes>
    </MemoryRouter>
  );
}

describe('FreightOrder 详情页', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('allowedNextStates 含 CONFIRMED 时渲染「确认委托」按钮', async () => {
    mockDetail.mockResolvedValue(makeDetail(['CONFIRMED']));

    renderDetail();

    await waitFor(() => {
      expect(screen.getByText('FO-2026-0001')).toBeInTheDocument();
    });
    expect(screen.getByRole('button', { name: '确认委托' })).toBeInTheDocument();
    // allowedNextStates 只有 CONFIRMED —— 不该出现取消按钮。
    expect(screen.queryByRole('button', { name: '取消委托' })).toBeNull();
  }, 30000);

  it('allowedNextStates 不含 CONFIRMED 时不渲染「确认委托」按钮', async () => {
    mockDetail.mockResolvedValue(makeDetail(['CANCELLED']));

    renderDetail();

    await waitFor(() => {
      expect(screen.getByText('FO-2026-0001')).toBeInTheDocument();
    });
    expect(screen.queryByRole('button', { name: '确认委托' })).toBeNull();
    expect(screen.getByRole('button', { name: '取消委托' })).toBeInTheDocument();
  }, 30000);

  it('allowedNextStates 含 CANCELLED 时渲染「取消委托」按钮', async () => {
    mockDetail.mockResolvedValue(makeDetail(['CANCELLED']));

    renderDetail();

    await waitFor(() => {
      expect(screen.getByText('FO-2026-0001')).toBeInTheDocument();
    });
    expect(screen.getByRole('button', { name: '取消委托' })).toBeInTheDocument();
  }, 30000);

  it('点「确认委托」调用 confirm 端点, 并带上当前委托单 id', async () => {
    mockDetail.mockResolvedValue(makeDetail(['CONFIRMED']));
    mockConfirm.mockResolvedValue(makeDetail(['CONFIRMED']));

    renderDetail();
    await waitFor(() => {
      expect(screen.getByText('FO-2026-0001')).toBeInTheDocument();
    });

    fireEvent.click(screen.getByRole('button', { name: '确认委托' }));

    await waitFor(() => {
      expect(mockConfirm).toHaveBeenCalledWith(123);
    });
  }, 30000);

  it('取消原因不足 5 个字时不发取消请求, 并给出错误提示', async () => {
    mockDetail.mockResolvedValue(makeDetail(['CANCELLED']));

    renderDetail();
    await waitFor(() => {
      expect(screen.getByText('FO-2026-0001')).toBeInTheDocument();
    });

    fireEvent.click(screen.getByRole('button', { name: '取消委托' }));

    const modal = await waitFor(() => {
      const el = document.querySelector('.ant-modal');
      expect(el).toBeTruthy();
      return el as HTMLElement;
    });
    const textarea = modal.querySelector('textarea') as HTMLTextAreaElement;
    expect(textarea).toBeTruthy();
    fireEvent.change(textarea, { target: { value: '不要了' } });

    const okBtn = modal.querySelector('.ant-modal-footer .ant-btn-primary');
    expect(okBtn).toBeTruthy();
    fireEvent.click(okBtn as HTMLElement);

    await waitFor(() => {
      expect(screen.getByText('取消原因至少 5 个字')).toBeInTheDocument();
    });
    expect(mockCancel).not.toHaveBeenCalled();
  }, 30000);
});
