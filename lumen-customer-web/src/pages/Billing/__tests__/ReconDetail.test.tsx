/**
 * 4.5C-2 — ReconDetail 页面测试 (异议提交门禁).
 */

import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import ReconDetail from '../ReconDetail';
import { portalBillingService } from '../../../services/portalBillingService';
import type { ReconView } from '../../../types/billing';

vi.mock('../../../services/portalBillingService');
const mockDetail = vi.mocked(portalBillingService.reconDetail);
const mockDispute = vi.mocked(portalBillingService.raiseDispute);

const navigateMock = vi.fn();
vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof import('react-router-dom')>('react-router-dom');
  return { ...actual, useNavigate: () => navigateMock };
});

const OPEN: ReconView = {
  id: 1, period: '2026-08', currency: 'CNY',
  expectedAmount: 100, actualAmount: 90, varianceAmount: -10,
  adjustmentId: 31, reconciliationStatus: 'ADJUSTMENT_CREATED', remark: null,
  disputeAmount: null, disputeReason: null, disputeAt: null, closed: false,
};

function renderDetail() {
  return render(
    <MemoryRouter initialEntries={['/reconciliation/1']}>
      <Routes>
        <Route path="/reconciliation/:id" element={<ReconDetail />} />
      </Routes>
    </MemoryRouter>
  );
}

describe('ReconDetail page', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('开放且未异议 → 展示异议表单', async () => {
    mockDetail.mockResolvedValue(OPEN);
    renderDetail();
    await waitFor(() => expect(screen.getByText('对差异提异议')).toBeInTheDocument());
    expect(screen.getByText('提交异议')).toBeInTheDocument();
  });

  it('闭期 → 不给表单, 提示已闭期', async () => {
    mockDetail.mockResolvedValue({ ...OPEN, closed: true });
    renderDetail();
    await waitFor(() => expect(screen.getByText('该会计期间已闭期, 不能提异议')).toBeInTheDocument());
    expect(screen.queryByText('提交异议')).not.toBeInTheDocument();
  });

  it('已异议 → 展示既有理由, 不给表单', async () => {
    mockDetail.mockResolvedValue({
      ...OPEN, disputeAt: '2026-09-01T10:00:00', disputeReason: '货没送到',
    });
    renderDetail();
    await waitFor(() => expect(screen.getByText('本期已提过异议, 财务处理后会推进审批')).toBeInTheDocument());
    expect(screen.getByText('货没送到')).toBeInTheDocument();
    expect(screen.queryByText('提交异议')).not.toBeInTheDocument();
  });

  it('理由为空 → 前端拦截, 不发请求', async () => {
    mockDetail.mockResolvedValue(OPEN);
    renderDetail();
    await waitFor(() => expect(screen.getByText('提交异议')).toBeInTheDocument());
    await userEvent.click(screen.getByText('提交异议'));
    await waitFor(() => expect(screen.getByText('请填写异议理由')).toBeInTheDocument());
    expect(mockDispute).not.toHaveBeenCalled();
  });

  it('填了理由 → 调 raiseDispute 并回列表', async () => {
    mockDetail.mockResolvedValue(OPEN);
    mockDispute.mockResolvedValue({
      reconId: 1, adjustmentId: 31, adjustmentStatus: 'PENDING_L1', disputeAt: '2026-09-01T10:00:00',
    });
    renderDetail();
    await waitFor(() => expect(screen.getByText('提交异议')).toBeInTheDocument());

    // 用 fireEvent.change 而不是 userEvent.type: CJK 字符逐字派发 key 事件
    // 在全量并行跑时会把这条用例拖到 5 秒超时线上 (实测 810ms → 满载时超时)。
    // fireEvent.change 一次到位, antd Form 照样收到 onChange。
    fireEvent.change(screen.getByLabelText('异议理由'), {
      target: { value: '9月3日那票没送到' },
    });
    await userEvent.click(screen.getByText('提交异议'));

    await waitFor(() => expect(mockDispute).toHaveBeenCalledWith(1, { reason: '9月3日那票没送到', claimedAmount: null }));
    await waitFor(() => expect(navigateMock).toHaveBeenCalledWith('/reconciliation'));
  });
});
