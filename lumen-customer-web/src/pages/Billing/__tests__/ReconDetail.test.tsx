/**
 * 4.5C-2 — ReconDetail 页面测试 (异议提交门禁 + 错误分流).
 */

import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { useEffect } from 'react';
import userEvent from '@testing-library/user-event';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { MemoryRouter, Route, Routes, createMemoryRouter, RouterProvider } from 'react-router-dom';
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

  it('无权限 403 → 提示"没有提异议的权限" (重试无用)', async () => {
    mockDetail.mockResolvedValue(OPEN);
    mockDispute.mockRejectedValue({ response: { status: 403, data: { code: 403003 } } });
    renderDetail();
    await waitFor(() => expect(screen.getByText('提交异议')).toBeInTheDocument());

    fireEvent.change(screen.getByLabelText('异议理由'), { target: { value: '这期有异议' } });
    await userEvent.click(screen.getByText('提交异议'));

    await waitFor(() =>
      expect(vi.mocked(message.error)).toHaveBeenCalledWith('您没有提异议的权限, 请联系管理员')
    );
  });

  it('提交成功 → 提示里显示中文状态而不是 PENDING_L1', async () => {
    mockDetail.mockResolvedValue(OPEN);
    mockDispute.mockResolvedValue({
      reconId: 1, adjustmentId: 31, adjustmentStatus: 'PENDING_L1', disputeAt: '2026-09-01T10:00:00',
    });
    renderDetail();
    await waitFor(() => expect(screen.getByText('提交异议')).toBeInTheDocument());

    fireEvent.change(screen.getByLabelText('异议理由'), { target: { value: '差额有误' } });
    await userEvent.click(screen.getByText('提交异议'));

    await waitFor(() =>
      expect(vi.mocked(message.success)).toHaveBeenCalledWith(
        '异议已提交, 对应调账单已进入 待 L1 审批'
      )
    );
  });

  it('主张金额超过 4 位小数 → 前端就舍入, 不会发 scale>4 的值给后端', async () => {
    mockDetail.mockResolvedValue(OPEN);
    mockDispute.mockResolvedValue({
      reconId: 1, adjustmentId: 31, adjustmentStatus: 'PENDING_L1', disputeAt: '2026-09-01T10:00:00',
    });
    renderDetail();
    await waitFor(() => expect(screen.getByText('提交异议')).toBeInTheDocument());

    // 用 fireEvent.change 而不是 userEvent.type: CJK 逐字派发 key 事件太慢,
    // 全量并行跑时会撞 5 秒 per-test 超时。
    fireEvent.change(screen.getByLabelText('异议理由'), { target: { value: '差额有误' } });

    // precision={4} 的舍入发生在 **blur** 时 (rc-input-number 的 getPrecision 在
    // userTyping=true 时返回 undefined, 输入过程中不会舍入; onBlur →
    // flushInputValue(false) 才按 precision 舍入)。所以这里必须先聚焦输入框,
    // 再点提交 —— 点按钮会先 blur 输入框 (舍入入库), 再触发提交, 与真实用户操作一致。
    const spin = screen.getByRole('spinbutton');
    await userEvent.click(spin);
    fireEvent.change(spin, { target: { value: '12.34567' } });
    await userEvent.click(screen.getByText('提交异议'));

    await waitFor(() => expect(mockDispute).toHaveBeenCalled());
    const payload = vi.mocked(portalBillingService.raiseDispute).mock.calls[0][1];
    expect(payload.claimedAmount).toBe(12.3457); // rc-input-number 舍入到 4 位
  });

  it('换 id 时清掉上一期的残留 —— 不会对着上一期的金额提异议', async () => {
    const RECON_1: ReconView = { ...OPEN, id: 1, period: '2026-07', expectedAmount: 100 };
    const RECON_2: ReconView = { ...OPEN, id: 2, period: '2026-08', expectedAmount: 999 };
    mockDetail.mockImplementation((id: number) => Promise.resolve(id === 1 ? RECON_1 : RECON_2));

    // 用 data router (createMemoryRouter) 而不是 MemoryRouter + <Routes>:
    // 后者没有暴露 history 的 navigate, 改不了 param; 而换 MemoryRouter 的
    // key 会强制重挂载 —— 那样就测不到"同路由换参数不卸载组件"这个真实语义。
    // data router 的 router.navigate 换 param 时组件保持挂载, 与 RRv6 生产行为一致。
    let mounts = 0;
    const Probe = () => {
      useEffect(() => {
        mounts += 1;
      }, []);
      return <ReconDetail />;
    };
    const router = createMemoryRouter(
      [{ path: '/reconciliation/:id', element: <Probe /> }],
      { initialEntries: ['/reconciliation/1'] },
    );
    render(<RouterProvider router={router} />);

    await waitFor(() => expect(screen.getByText('100 CNY')).toBeInTheDocument());

    // 填上一期的理由和金额 —— 换 id 后这些必须被清掉。
    fireEvent.change(screen.getByLabelText('异议理由'), { target: { value: '上一期的理由' } });
    fireEvent.change(screen.getByRole('spinbutton'), { target: { value: '12.34' } });

    await act(async () => {
      await router.navigate('/reconciliation/2');
    });

    // 新数据到达后, 旧金额不再显示, 新金额显示出来。
    await waitFor(() => expect(screen.getByText('999 CNY')).toBeInTheDocument());
    expect(screen.queryByText('100 CNY')).not.toBeInTheDocument();

    // 组件**没有卸载** (React Router 同路由换 param 不重挂载) —— 所以上一期的
    // 表单残留必须由组件自己清, 否则商家会拿着上一期的金额去提这一期的异议。
    expect(mounts).toBe(1);
    expect((screen.getByLabelText('异议理由') as HTMLTextAreaElement).value).toBe('');
    expect((screen.getByRole('spinbutton') as HTMLInputElement).value).toBe('');
  });
});
