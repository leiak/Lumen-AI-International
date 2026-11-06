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

/** 未闭期行 —— 数字还在变, 商家不能提异议。 */
const OPEN_ROW: ReconView = {
  id: 1, period: '2026-08', currency: 'CNY',
  expectedAmount: 100, actualAmount: 90, varianceAmount: -10,
  adjustmentId: 31, reconciliationStatus: 'ADJUSTMENT_CREATED', remark: null,
  disputeAmount: null, disputeReason: null, disputeAt: null, closed: false,
};

/** 已闭期行 —— 已闭期才可提异议。 */
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
    expect(screen.getByText('-10 CNY')).toBeInTheDocument();
  });

  it('未闭期行显示"数据可能变动"且是 warning 色 —— 不是绿色的"开放"', async () => {
    mockList.mockResolvedValue({ records: [OPEN_ROW], total: 1, page: 1, size: 20 });
    renderList();
    await waitFor(() => expect(screen.getByText('数据可能变动')).toBeInTheDocument());
    // 绿色的「开放」语义是"好消息", 与实际含义 (数字还在变, 别提异议) 正好相反。
    expect(screen.queryByText('开放')).not.toBeInTheDocument();
    expect(screen.getByText('数据可能变动')).toHaveClass('ant-tag-warning');
  });

  it('闭期行显示"已闭期"', async () => {
    mockList.mockResolvedValue({ records: [CLOSED_ROW], total: 1, page: 1, size: 20 });
    renderList();
    await waitFor(() => expect(screen.getByText('已闭期')).toBeInTheDocument());
    expect(screen.queryByText('数据可能变动')).not.toBeInTheDocument();
  });

  it('渲染对账状态列 —— 商家看得见自己那笔差异到哪一步了', async () => {
    mockList.mockResolvedValue({
      records: [
        { ...OPEN_ROW, id: 1, period: '2026-04', reconciliationStatus: 'PENDING' },
        { ...OPEN_ROW, id: 2, period: '2026-05', reconciliationStatus: 'ADJUSTMENT_CREATED' },
        { ...OPEN_ROW, id: 3, period: '2026-06', reconciliationStatus: 'RESOLVED' },
        { ...OPEN_ROW, id: 4, period: '2026-07', reconciliationStatus: 'WAIVED' },
      ],
      total: 4, page: 1, size: 20,
    });
    renderList();
    await waitFor(() => expect(screen.getByText('2026-04')).toBeInTheDocument());
    expect(screen.getByText('未对账')).toBeInTheDocument();
    expect(screen.getByText('已建调账单')).toBeInTheDocument();
    expect(screen.getByText('已结清')).toBeInTheDocument();
    expect(screen.getByText('已豁免')).toBeInTheDocument();
  });

  it('对账状态为空时显示占位符而不是空白', async () => {
    mockList.mockResolvedValue({
      records: [{ ...OPEN_ROW, reconciliationStatus: null }],
      total: 1, page: 1, size: 20,
    });
    renderList();
    await waitFor(() => expect(screen.getByText('2026-08')).toBeInTheDocument());
    expect(screen.getByText('-')).toBeInTheDocument();
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

  it('有分页器 —— 不再提示"截断 / 联系客服" (那是分页之前的说法)', async () => {
    mockList.mockResolvedValue({ records: [OPEN_ROW], total: 35, page: 1, size: 20 });
    renderList();
    await waitFor(() => expect(screen.getByText('2026-08')).toBeInTheDocument());
    expect(screen.queryByText(/当前仅显示前/)).not.toBeInTheDocument();
    expect(screen.queryByText(/联系客服/)).not.toBeInTheDocument();
  });

  it('列表带币种列 —— 同商家多币种时能分清哪行是哪行', async () => {
    mockList.mockResolvedValue({
      records: [
        OPEN_ROW,
        { ...OPEN_ROW, id: 2, period: '2026-08', currency: 'USD', expectedAmount: 80, actualAmount: 80, varianceAmount: 0 },
      ],
      total: 2, page: 1, size: 20,
    });
    renderList();
    await waitFor(() => expect(screen.getByText('CNY')).toBeInTheDocument());
    expect(screen.getByText('USD')).toBeInTheDocument();
  });
});
