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

vi.mock('../../../services/portalBillingService');

const mockRecons = vi.mocked(portalBillingService.listRecons);
const mockInvoices = vi.mocked(portalBillingService.listInvoices);
const mockPayments = vi.mocked(portalBillingService.listPayments);
const mockAdjustments = vi.mocked(portalBillingService.listAdjustments);

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
});
