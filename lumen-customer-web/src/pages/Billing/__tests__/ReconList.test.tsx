/**
 * 4.5C-2 — ReconList 页面测试.
 */

import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { MemoryRouter } from 'react-router-dom';
import { message } from 'antd';
import ReconList from '../ReconList';
import { portalBillingService } from '../../../services/portalBillingService';
import type { ReconView } from '../../../types/billing';

vi.mock('../../../services/portalBillingService');
const mockList = vi.mocked(portalBillingService.listRecons);
const mockExport = vi.mocked(portalBillingService.exportRecons);

vi.mock('antd', async () => {
  const actual = await vi.importActual<typeof import('antd')>('antd');
  return { ...actual, message: { success: vi.fn(), error: vi.fn() } };
});

const OPEN_ROW: ReconView = {
  id: 1, period: '2026-08', currency: 'CNY',
  expectedAmount: 100, actualAmount: 90, varianceAmount: -10,
  adjustmentId: 31, reconciliationStatus: 'ADJUSTMENT_CREATED', remark: null,
  disputeAmount: null, disputeReason: null, disputeAt: null, closed: false,
};

const CLOSED_ROW: ReconView = { ...OPEN_ROW, id: 2, period: '2026-07', closed: true };

function renderList() {
  return render(
    <MemoryRouter initialEntries={['/reconciliation']}>
      <ReconList />
    </MemoryRouter>
  );
}

describe('ReconList page', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('渲染对账行与差异金额', async () => {
    mockList.mockResolvedValue({ records: [OPEN_ROW], total: 1, page: 1, size: 20 });
    renderList();
    await waitFor(() => expect(screen.getByText('2026-08')).toBeInTheDocument());
    expect(screen.getByText('-10')).toBeInTheDocument();
    expect(screen.getByText('开放')).toBeInTheDocument();
  });

  it('闭期行显示"已闭期"', async () => {
    mockList.mockResolvedValue({ records: [CLOSED_ROW], total: 1, page: 1, size: 20 });
    renderList();
    await waitFor(() => expect(screen.getByText('已闭期')).toBeInTheDocument());
  });

  it('已提异议的行显示"已提异议"', async () => {
    mockList.mockResolvedValue({
      records: [{ ...OPEN_ROW, disputeAt: '2026-09-01T10:00:00' }],
      total: 1, page: 1, size: 20,
    });
    renderList();
    await waitFor(() => expect(screen.getByText('已提异议')).toBeInTheDocument());
  });

  it('空列表不报错', async () => {
    mockList.mockResolvedValue({ records: [], total: 0, page: 1, size: 20 });
    renderList();
    await waitFor(() => expect(screen.getByText('月结对账单')).toBeInTheDocument());
    expect(screen.queryByText('2026-08')).not.toBeInTheDocument();
  });

  it('加载失败展示错误提示', async () => {
    mockList.mockRejectedValue(new Error('boom'));
    renderList();
    await waitFor(() => expect(screen.getByText('加载失败, 请稍后重试')).toBeInTheDocument());
  });

  it('点导出按钮调 exportRecons', async () => {
    mockList.mockResolvedValue({ records: [], total: 0, page: 1, size: 20 });
    mockExport.mockResolvedValue(undefined);
    renderList();
    await userEvent.click(await screen.findByText('导出 Excel'));
    await waitFor(() => expect(mockExport).toHaveBeenCalledTimes(1));
  });

  it('导出失败提示错误', async () => {
    mockList.mockResolvedValue({ records: [], total: 0, page: 1, size: 20 });
    mockExport.mockRejectedValue(new Error('boom'));
    renderList();
    await userEvent.click(await screen.findByText('导出 Excel'));
    await waitFor(() =>
      expect(vi.mocked(message.error)).toHaveBeenCalledWith('导出失败, 请稍后重试')
    );
  });

  it('详情链接指向 /reconciliation/{id}', async () => {
    mockList.mockResolvedValue({ records: [OPEN_ROW], total: 1, page: 1, size: 20 });
    renderList();
    const link = await screen.findByRole('link', { name: '查看详情' });
    expect(link).toHaveAttribute('href', '/reconciliation/1');
  });

  it('后端 total 大于本页条数时提示截断', async () => {
    mockList.mockResolvedValue({ records: [OPEN_ROW], total: 35, page: 1, size: 20 });
    renderList();
    await waitFor(() =>
      expect(screen.getByText('共 35 条, 当前仅显示前 1 条')).toBeInTheDocument()
    );
  });
});
