/**
 * 4.5C-2.1 — 商家门户 4 个列表页的服务端分页 (缺口 #9)。
 *
 * 关键断言: 点"下一页" → **带着新的 page 重新请求**, 而不是本地切已有的 rows。
 */

import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { MemoryRouter } from 'react-router-dom';
import ReconList from '../ReconList';
import InvoiceList from '../InvoiceList';
import PaymentList from '../PaymentList';
import AdjustmentList from '../AdjustmentList';
import { portalBillingService } from '../../../services/portalBillingService';
import type { ReconView } from '../../../types/billing';

vi.mock('../../../services/portalBillingService');

const mockRecons = vi.mocked(portalBillingService.listRecons);
const mockInvoices = vi.mocked(portalBillingService.listInvoices);
const mockPayments = vi.mocked(portalBillingService.listPayments);
const mockAdjustments = vi.mocked(portalBillingService.listAdjustments);

/** 一行对账数据 —— 用来验证「失败时表格里没有旧数据」。 */
const RECON_ROW: ReconView = {
  id: 1, period: '2026-08', currency: 'CNY',
  expectedAmount: 100, actualAmount: 90, varianceAmount: -10,
  adjustmentId: null, reconciliationStatus: 'PENDING', remark: null,
  disputeAmount: null, disputeReason: null, disputeAt: null, closed: true,
};

describe('商家门户列表分页', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('ReconList: 点下一页 → 用 page=2 重新请求', async () => {
    mockRecons.mockResolvedValue({ records: [], total: 40, page: 1, size: 20 });
    render(<MemoryRouter><ReconList /></MemoryRouter>);
    await waitFor(() => expect(mockRecons).toHaveBeenCalledTimes(1));
    expect(mockRecons).toHaveBeenLastCalledWith(undefined, 1, 20);

    await userEvent.click(await screen.findByTitle('2'));

    await waitFor(() => expect(mockRecons).toHaveBeenLastCalledWith(undefined, 2, 20));
  });

  it('PaymentList: 点下一页 → 用 page=2 重新请求', async () => {
    mockPayments.mockResolvedValue({ records: [], total: 40, page: 1, size: 20 });
    render(<MemoryRouter><PaymentList /></MemoryRouter>);
    await waitFor(() => expect(mockPayments).toHaveBeenCalledTimes(1));
    await userEvent.click(await screen.findByTitle('2'));
    await waitFor(() => expect(mockPayments).toHaveBeenLastCalledWith(undefined, 2, 20));
  });

  it('InvoiceList: 点下一页 → 用 page=2 重新请求', async () => {
    mockInvoices.mockResolvedValue({ records: [], total: 40, page: 1, size: 20 });
    render(<MemoryRouter><InvoiceList /></MemoryRouter>);
    await waitFor(() => expect(mockInvoices).toHaveBeenCalledTimes(1));
    await userEvent.click(await screen.findByTitle('2'));
    await waitFor(() => expect(mockInvoices).toHaveBeenLastCalledWith(undefined, 2, 20));
  });

  it('AdjustmentList: 点下一页 → 用 page=2 重新请求', async () => {
    mockAdjustments.mockResolvedValue({ records: [], total: 40, page: 1, size: 20 });
    render(<MemoryRouter><AdjustmentList /></MemoryRouter>);
    await waitFor(() => expect(mockAdjustments).toHaveBeenCalledTimes(1));
    await userEvent.click(await screen.findByTitle('2'));
    await waitFor(() => expect(mockAdjustments).toHaveBeenLastCalledWith(2, 20));
  });

  it('分页器显示总数 —— 商家知道一共多少条', async () => {
    mockRecons.mockResolvedValue({ records: [], total: 40, page: 1, size: 20 });
    render(<MemoryRouter><ReconList /></MemoryRouter>);
    await waitFor(() => expect(screen.getByText('共 40 条')).toBeInTheDocument());
  });

  // ---- 以下 3 条是复审补记新加的 ----

  it('ReconList: 改页大小 → 用新 size 重新请求, 且回到第 1 页', async () => {
    mockRecons.mockResolvedValue({ records: [], total: 100, page: 1, size: 20 });
    render(<MemoryRouter><ReconList /></MemoryRouter>);
    await waitFor(() => expect(mockRecons).toHaveBeenCalledTimes(1));

    // 先翻到第 3 页 —— 改页大小后必须回到第 1 页, 否则会停在一个空页上。
    await userEvent.click(await screen.findByTitle('3'));
    await waitFor(() => expect(mockRecons).toHaveBeenLastCalledWith(undefined, 3, 20));

    // 打开 antd 的页大小下拉 (rc-pagination 的 Options 组件)。
    // 注意: 页大小下拉的选项 title 是 '50 / page' **不是** '50'
    // (页码 <li> 才带纯数字 title) —— 见 rc-pagination/lib/Options.js。
    const trigger = document.querySelector('.ant-pagination-options .ant-select-selector');
    expect(trigger).not.toBeNull();
    await userEvent.click(trigger as HTMLElement);
    await userEvent.click(await screen.findByTitle('50 / page'));

    await waitFor(() => expect(mockRecons).toHaveBeenLastCalledWith(undefined, 1, 50));
    // 超时放宽 —— 这一条要走完整的 antd 下拉交互 (打开 + 选中),
    // jsdom + antd 一次 userEvent.click 要 1 秒以上, 默认 5 秒不够。
  }, 30000);

  it('ReconList: 翻页失败 → 显示错误提示且表格里没有上一页的数据', async () => {
    mockRecons.mockResolvedValueOnce({ records: [RECON_ROW], total: 40, page: 1, size: 20 });
    mockRecons.mockRejectedValueOnce(new Error('boom'));
    render(<MemoryRouter><ReconList /></MemoryRouter>);
    await waitFor(() => expect(screen.getByText('2026-08')).toBeInTheDocument());

    await userEvent.click(await screen.findByTitle('2'));

    await waitFor(() => expect(screen.getByText('加载失败, 请稍后重试')).toBeInTheDocument());
    // 分页器说「第 2 页」, 表格里却还是第 1 页的数据 —— 那是最迷惑商家的状态。
    expect(screen.queryByText('2026-08')).not.toBeInTheDocument();
  });

  it('ReconList: 失败后点「重试」→ 重新请求, 错误提示消失', async () => {
    mockRecons.mockRejectedValueOnce(new Error('boom'));
    render(<MemoryRouter><ReconList /></MemoryRouter>);
    await waitFor(() => expect(screen.getByText('加载失败, 请稍后重试')).toBeInTheDocument());
    expect(mockRecons).toHaveBeenCalledTimes(1);

    mockRecons.mockResolvedValue({ records: [], total: 40, page: 1, size: 20 });
    await userEvent.click(screen.getByRole('button', { name: '重试' }));

    await waitFor(() => expect(mockRecons).toHaveBeenCalledTimes(2));
    await waitFor(() => expect(screen.queryByText('加载失败, 请稍后重试')).not.toBeInTheDocument());
  });
});
