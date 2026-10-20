/**
 * 4.5B.3-T3.3 — OrderList page tests.
 */

import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { MemoryRouter } from 'react-router-dom';
import OrderList from '../OrderList';
import { portalOrderService } from '../../../services/portalOrderService';
import type { PortalOrderListItem } from '../../../types/order';

vi.mock('../../../services/portalOrderService');
const mockList = vi.mocked(portalOrderService.list);

const navigateMock = vi.fn();
vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof import('react-router-dom')>('react-router-dom');
  return { ...actual, useNavigate: () => navigateMock };
});

const ROWS: PortalOrderListItem[] = [
  {
    doId: 1, doNo: 'PO-001', status: 'PENDING_DISPATCH',
    originAddress: '上海市 A', destAddress: '北京市 B',
    createdAt: '2026-10-15T08:00:00', signedAt: null,
    plannedPickupWindowStart: '2026-10-20T14:00:00', plannedPickupWindowEnd: '2026-10-20T18:00:00',
  },
  {
    doId: 2, doNo: 'PO-002', status: 'DELIVERED',
    originAddress: '上海市 C', destAddress: '广州市 D',
    createdAt: '2026-10-15T09:00:00', signedAt: '2026-10-16T10:00:00',
    plannedPickupWindowStart: '2026-10-19T09:00:00', plannedPickupWindowEnd: null,
  },
];

function renderList() {
  return render(
    <MemoryRouter initialEntries={['/orders']}>
      <OrderList />
    </MemoryRouter>
  );
}

describe('OrderList page', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('空列表展示空状态', async () => {
    mockList.mockResolvedValue({ records: [], total: 0, page: 1, size: 10 });

    renderList();

    await waitFor(() => {
      expect(mockList).toHaveBeenCalled();
    });
    await waitFor(() => {
      expect(screen.getAllByText('暂无数据').length).toBeGreaterThanOrEqual(1);
    });
  });

  it('渲染订单行并带默认分页参数', async () => {
    mockList.mockResolvedValue({ records: ROWS, total: ROWS.length, page: 1, size: 10 });

    renderList();

    await waitFor(() => {
      expect(screen.getByText('PO-001')).toBeInTheDocument();
      expect(screen.getByText('PO-002')).toBeInTheDocument();
    });
    // 取件时间窗渲染成 "2026-10-20 14:00 ~ 2026-10-20 18:00"
    expect(screen.getByText('2026-10-20 14:00 ~ 2026-10-20 18:00')).toBeInTheDocument();
    expect(mockList).toHaveBeenCalledWith({
      doNo: undefined, status: undefined, page: 1, size: 10,
    });
  });

  it('按运单号过滤后带参数重新请求', async () => {
    mockList.mockResolvedValue({ records: [ROWS[0]], total: 1, page: 1, size: 10 });

    renderList();
    await waitFor(() => expect(mockList).toHaveBeenCalledTimes(1));

    const doNoInput = document.getElementById('doNo') as HTMLInputElement | null;
    expect(doNoInput).not.toBeNull();
    await userEvent.type(doNoInput!, 'PO-001');
    await userEvent.click(screen.getByText('查 询'));

    await waitFor(() => {
      expect(mockList.mock.calls.find((c) => c[0]?.doNo === 'PO-001')).toBeDefined();
    });
  });

  it('工具栏"下单"按钮跳转到新建页', async () => {
    mockList.mockResolvedValue({ records: [], total: 0, page: 1, size: 10 });

    renderList();
    await waitFor(() => expect(mockList).toHaveBeenCalled());

    // antd 在两个 CJK 字符之间插空格 → "下 单"
    await userEvent.click(await screen.findByText('下 单'));

    expect(navigateMock).toHaveBeenCalledWith('/orders/new');
  });
});