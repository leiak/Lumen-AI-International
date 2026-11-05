/**
 * 4.5C-2 — PortalLayout 侧边栏菜单选中态.
 *
 * 守住两个东西:
 *   1. PortalLayout 能渲染出来 —— 4.5A 起它用 react-router 的 useMatches() 取
 *      选中 key, 但本项目挂的是普通 <BrowserRouter>, useMatches() 在那里面直接
 *      抛 "useMatches must be used within a data router." —— 整个门户布局渲染不出来。
 *      现在改成按 pathname 判定 (selectMenuKey), 不再依赖 data router。
 *   2. 每个菜单项在自己的路由上高亮, 且**详情页**仍高亮所属大项。
 */

import { render, screen, waitFor } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { MemoryRouter } from 'react-router-dom';
import PortalLayout from '../PortalLayout';
import { portalAuthService } from '../../services/portalAuthService';
import { useAuthStore } from '../../store/authStore';

vi.mock('../../services/portalAuthService');

const mockMe = vi.mocked(portalAuthService.me);

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <PortalLayout />
    </MemoryRouter>
  );
}

/** 当前被选中 (antd 加 ant-menu-item-selected) 的菜单项文字。 */
async function selectedLabels(): Promise<string[]> {
  await waitFor(() => expect(screen.getByText('运单追踪')).toBeInTheDocument());
  return screen
    .getAllByRole('menuitem')
    .filter((el) => el.className.includes('ant-menu-item-selected'))
    .map((el) => el.textContent ?? '');
}

describe('PortalLayout 菜单选中态', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    useAuthStore.setState({ token: 'tok', activeCustomerId: 7, username: 'u' });
    mockMe.mockResolvedValue({
      userId: 1, username: 'u', realName: 'U', activeCustomerId: 7, activeCustomerName: 'C',
      allChoices: [{ id: 7, code: 'C', name: 'C', isPrimary: true }],
    });
  });

  it('/reconciliation 高亮"对账单"', async () => {
    renderAt('/reconciliation');
    expect(await selectedLabels()).toEqual(['对账单']);
  });

  it('/reconciliation/1 详情仍高亮"对账单"', async () => {
    renderAt('/reconciliation/1');
    expect(await selectedLabels()).toEqual(['对账单']);
  });

  it('/invoices 高亮"发票"', async () => {
    renderAt('/invoices');
    expect(await selectedLabels()).toEqual(['发票']);
  });

  it('/payments 高亮"付款记录"', async () => {
    renderAt('/payments');
    expect(await selectedLabels()).toEqual(['付款记录']);
  });

  it('/adjustments 高亮"我的调账单"', async () => {
    renderAt('/adjustments');
    expect(await selectedLabels()).toEqual(['我的调账单']);
  });

  it('/orders/new 高亮"下单" 而不是"我的订单"', async () => {
    renderAt('/orders/new');
    expect(await selectedLabels()).toEqual(['下单']);
  });

  it('/orders/123 详情高亮"我的订单"', async () => {
    renderAt('/orders/123');
    expect(await selectedLabels()).toEqual(['我的订单']);
  });

  it('/track 高亮"运单追踪"', async () => {
    renderAt('/track');
    expect(await selectedLabels()).toEqual(['运单追踪']);
  });

  it('/profile 高亮"我的资料"', async () => {
    renderAt('/profile');
    expect(await selectedLabels()).toEqual(['我的资料']);
  });
});
