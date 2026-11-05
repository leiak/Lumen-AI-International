/**
 * 4.5C-2 — ReconDetail 页面测试 (异议提交门禁 + 错误分流).
 */

import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { message } from 'antd';
import ReconDetail from '../ReconDetail';
import { portalBillingService } from '../../../services/portalBillingService';
import type { ReconView } from '../../../types/billing';

vi.mock('../../../services/portalBillingService');
const mockDetail = vi.mocked(portalBillingService.reconDetail);
const mockDispute = vi.mocked(portalBillingService.raiseDispute);

vi.mock('antd', async () => {
  const actual = await vi.importActual<typeof import('antd')>('antd');
  return { ...actual, message: { success: vi.fn(), error: vi.fn() } };
});

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

/** 造一个带门户错误码的 axios 形状错误 (R 信封: { code, message, data })。 */
function portalError(code: number) {
  return { response: { status: code === 409002 ? 409 : 400, data: { code, message: 'internal' } } };
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

  it('理由只填空格 → 前端拦截, 不发请求', async () => {
    mockDetail.mockResolvedValue(OPEN);
    renderDetail();
    await waitFor(() => expect(screen.getByText('提交异议')).toBeInTheDocument());
    fireEvent.change(screen.getByLabelText('异议理由'), { target: { value: '   ' } });
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

  it('填了主张金额 → payload 带 claimedAmount', async () => {
    mockDetail.mockResolvedValue(OPEN);
    mockDispute.mockResolvedValue({
      reconId: 1, adjustmentId: 31, adjustmentStatus: 'PENDING_L1', disputeAt: '2026-09-01T10:00:00',
    });
    renderDetail();
    await waitFor(() => expect(screen.getByText('提交异议')).toBeInTheDocument());

    fireEvent.change(screen.getByLabelText('异议理由'), { target: { value: '差额算错了' } });
    fireEvent.change(screen.getByRole('spinbutton'), { target: { value: '12.34' } });
    await userEvent.click(screen.getByText('提交异议'));

    await waitFor(() =>
      expect(mockDispute).toHaveBeenCalledWith(1, { reason: '差额算错了', claimedAmount: 12.34 })
    );
  });

  it('详情 404 → 显示"对账单不存在"', async () => {
    mockDetail.mockRejectedValue({ response: { status: 404 } });
    renderDetail();
    await waitFor(() => expect(screen.getByText('对账单不存在')).toBeInTheDocument());
    expect(screen.queryByText('加载失败, 请稍后重试')).not.toBeInTheDocument();
  });

  it('详情非 404 失败 (断网 / 500) → 显示"加载失败"', async () => {
    mockDetail.mockRejectedValue(new Error('network down'));
    renderDetail();
    await waitFor(() => expect(screen.getByText('加载失败, 请稍后重试')).toBeInTheDocument());
    expect(screen.queryByText('对账单不存在')).not.toBeInTheDocument();
  });

  it('异议被拒 409_002 → 提示"该期不可提异议"', async () => {
    mockDetail.mockResolvedValue(OPEN);
    mockDispute.mockRejectedValue(portalError(409002));
    renderDetail();
    await waitFor(() => expect(screen.getByText('提交异议')).toBeInTheDocument());

    fireEvent.change(screen.getByLabelText('异议理由'), { target: { value: '这期我确实有异议' } });
    await userEvent.click(screen.getByText('提交异议'));

    await waitFor(() =>
      expect(vi.mocked(message.error)).toHaveBeenCalledWith(
        '该期对账单当前不可提异议 (已异议过 / 期间已闭期 / 无关联调账单)'
      )
    );
    expect(navigateMock).not.toHaveBeenCalled();
  });

  it('异议金额超范围 400_004 → 提示金额超范围', async () => {
    mockDetail.mockResolvedValue(OPEN);
    mockDispute.mockRejectedValue(portalError(400004));
    renderDetail();
    await waitFor(() => expect(screen.getByText('提交异议')).toBeInTheDocument());

    fireEvent.change(screen.getByLabelText('异议理由'), { target: { value: '主张金额' } });
    await userEvent.click(screen.getByText('提交异议'));

    await waitFor(() =>
      expect(vi.mocked(message.error)).toHaveBeenCalledWith(
        '主张金额超出可表示范围 (最多 14 位整数 + 4 位小数)'
      )
    );
  });
});
