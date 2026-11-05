/**
 * 4.5C-2 — ReconDetail 页面测试 (异议提交门禁 + 错误分流).
 *
 * ⚠️ 闭期闸门的方向: **已闭期才可提异议** (spec §7.2) —— 未闭期时这一期的
 * 数字还会变, 提异议没有意义。4.5C-2 最初把这条写反了 (前后端 + 文案 + 测试
 * 一起反), 后端已在 888050c 订正, 这里同步对齐。
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

/** 已闭期 —— 唯一能提异议的那种。 */
const CLOSED: ReconView = {
  id: 1, period: '2026-08', currency: 'CNY',
  expectedAmount: 100, actualAmount: 90, varianceAmount: -10,
  adjustmentId: 31, reconciliationStatus: 'ADJUSTMENT_CREATED', remark: null,
  disputeAmount: null, disputeReason: null, disputeAt: null, closed: true,
};

/** 未闭期 —— 数字还在变, 商家不能提异议。 */
const NOT_CLOSED: ReconView = { ...CLOSED, closed: false };

/** 理由最短 10 字 (后端 ADJUSTMENT_REASON_MIN_LEN) —— 下面所有真正提交的用例都用 ≥ 10 字的。 */
const LONG_REASON = '9月3日那票货没送到, 请核对';
const OTHER_LONG_REASON = '差额算错了, 请重新核对金额';

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

  it('已闭期且未异议 → 展示异议表单', async () => {
    mockDetail.mockResolvedValue(CLOSED);
    renderDetail();
    await waitFor(() => expect(screen.getByText('对差异提异议')).toBeInTheDocument());
    expect(screen.getByText('提交异议')).toBeInTheDocument();
    // 已闭期不该再提示"尚未闭期"。
    expect(screen.queryByText('该会计期间尚未闭期, 数字仍可能变动, 不能提异议')).not.toBeInTheDocument();
  });

  it('未闭期 → 不给表单, 提示数字仍可能变动', async () => {
    mockDetail.mockResolvedValue(NOT_CLOSED);
    renderDetail();
    await waitFor(() =>
      expect(screen.getByText('该会计期间尚未闭期, 数字仍可能变动, 不能提异议')).toBeInTheDocument()
    );
    expect(screen.queryByText('提交异议')).not.toBeInTheDocument();
  });

  it('期间状态: 已闭期显示"已闭期", 未闭期显示"数据可能变动" (不用"开放")', async () => {
    mockDetail.mockResolvedValue(CLOSED);
    renderDetail();
    await waitFor(() => expect(screen.getByText('已闭期')).toBeInTheDocument());
    expect(screen.queryByText('数据可能变动')).not.toBeInTheDocument();
  });

  it('未闭期时期间状态显示"数据可能变动"', async () => {
    mockDetail.mockResolvedValue(NOT_CLOSED);
    renderDetail();
    await waitFor(() => expect(screen.getByText('数据可能变动')).toBeInTheDocument());
    expect(screen.queryByText('已闭期')).not.toBeInTheDocument();
    expect(screen.queryByText('开放')).not.toBeInTheDocument();
  });

  it('展示对账状态 —— 商家看得见自己那笔差异到哪一步了', async () => {
    mockDetail.mockResolvedValue({ ...CLOSED, reconciliationStatus: 'ADJUSTMENT_CREATED' });
    renderDetail();
    await waitFor(() => expect(screen.getByText('已建调账单')).toBeInTheDocument());
  });

  it('已异议 → 展示既有理由, 不给表单', async () => {
    mockDetail.mockResolvedValue({
      ...CLOSED, disputeAt: '2026-09-01T10:00:00', disputeReason: '货没送到',
    });
    renderDetail();
    await waitFor(() => expect(screen.getByText('本期已提过异议, 财务处理后会推进审批')).toBeInTheDocument());
    expect(screen.getByText('货没送到')).toBeInTheDocument();
    expect(screen.queryByText('提交异议')).not.toBeInTheDocument();
  });

  it('理由为空 → 前端拦截, 不发请求', async () => {
    mockDetail.mockResolvedValue(CLOSED);
    renderDetail();
    await waitFor(() => expect(screen.getByText('提交异议')).toBeInTheDocument());
    await userEvent.click(screen.getByText('提交异议'));
    await waitFor(() => expect(screen.getByText('请填写异议理由')).toBeInTheDocument());
    expect(mockDispute).not.toHaveBeenCalled();
  });

  it('理由只填空格 → 前端拦截, 不发请求', async () => {
    mockDetail.mockResolvedValue(CLOSED);
    renderDetail();
    await waitFor(() => expect(screen.getByText('提交异议')).toBeInTheDocument());
    fireEvent.change(screen.getByLabelText('异议理由'), { target: { value: '   ' } });
    await userEvent.click(screen.getByText('提交异议'));
    await waitFor(() => expect(screen.getByText('请填写异议理由')).toBeInTheDocument());
    expect(mockDispute).not.toHaveBeenCalled();
  });

  it('理由少于 10 字 → 前端拦截, 不发请求', async () => {
    mockDetail.mockResolvedValue(CLOSED);
    renderDetail();
    await waitFor(() => expect(screen.getByText('提交异议')).toBeInTheDocument());
    fireEvent.change(screen.getByLabelText('异议理由'), { target: { value: '差额算错了' } });
    await userEvent.click(screen.getByText('提交异议'));
    await waitFor(() => expect(screen.getByText('异议理由至少 10 个字')).toBeInTheDocument());
    expect(mockDispute).not.toHaveBeenCalled();
  });

  it('填了理由 → 调 raiseDispute 并跳「我的调账单」', async () => {
    mockDetail.mockResolvedValue(CLOSED);
    mockDispute.mockResolvedValue({
      reconId: 1, adjustmentId: 31, adjustmentStatus: 'PENDING_L1', disputeAt: '2026-09-01T10:00:00',
    });
    renderDetail();
    await waitFor(() => expect(screen.getByText('提交异议')).toBeInTheDocument());

    // 用 fireEvent.change 而不是 userEvent.type: CJK 字符逐字派发 key 事件
    // 在全量并行跑时会把这条用例拖到 5 秒超时线上 (实测 810ms → 满载时超时)。
    // fireEvent.change 一次到位, antd Form 照样收到 onChange。
    fireEvent.change(screen.getByLabelText('异议理由'), {
      target: { value: LONG_REASON },
    });
    await userEvent.click(screen.getByText('提交异议'));

    await waitFor(() =>
      expect(mockDispute).toHaveBeenCalledWith(1, { reason: LONG_REASON, claimedAmount: null })
    );
    // spec §9.4: 提交成功后跳「我的调账单」—— 商家要看那张 PENDING_L1 的调账单。
    await waitFor(() => expect(navigateMock).toHaveBeenCalledWith('/adjustments'));
  });

  it('填了主张金额 → payload 带 claimedAmount', async () => {
    mockDetail.mockResolvedValue(CLOSED);
    mockDispute.mockResolvedValue({
      reconId: 1, adjustmentId: 31, adjustmentStatus: 'PENDING_L1', disputeAt: '2026-09-01T10:00:00',
    });
    renderDetail();
    await waitFor(() => expect(screen.getByText('提交异议')).toBeInTheDocument());

    fireEvent.change(screen.getByLabelText('异议理由'), { target: { value: OTHER_LONG_REASON } });
    fireEvent.change(screen.getByRole('spinbutton'), { target: { value: '12.34' } });
    await userEvent.click(screen.getByText('提交异议'));

    await waitFor(() =>
      expect(mockDispute).toHaveBeenCalledWith(1, { reason: OTHER_LONG_REASON, claimedAmount: 12.34 })
    );
  });

  it('主张金额填 0 → 前端拦截, 不发请求', async () => {
    mockDetail.mockResolvedValue(CLOSED);
    renderDetail();
    await waitFor(() => expect(screen.getByText('提交异议')).toBeInTheDocument());

    fireEvent.change(screen.getByLabelText('异议理由'), { target: { value: LONG_REASON } });
    fireEvent.change(screen.getByRole('spinbutton'), { target: { value: '0' } });
    await userEvent.click(screen.getByText('提交异议'));

    await waitFor(() =>
      expect(screen.getByText('主张金额不能为 0 (只质疑差额请留空)')).toBeInTheDocument()
    );
    expect(mockDispute).not.toHaveBeenCalled();
  });

  it('主张金额填负数 → 允许提交 (商家主张的是差额, 负数合法)', async () => {
    mockDetail.mockResolvedValue(CLOSED);
    mockDispute.mockResolvedValue({
      reconId: 1, adjustmentId: 31, adjustmentStatus: 'PENDING_L1', disputeAt: '2026-09-01T10:00:00',
    });
    renderDetail();
    await waitFor(() => expect(screen.getByText('提交异议')).toBeInTheDocument());

    fireEvent.change(screen.getByLabelText('异议理由'), { target: { value: LONG_REASON } });
    fireEvent.change(screen.getByRole('spinbutton'), { target: { value: '-12' } });
    await userEvent.click(screen.getByText('提交异议'));

    await waitFor(() =>
      expect(mockDispute).toHaveBeenCalledWith(1, { reason: LONG_REASON, claimedAmount: -12 })
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
    mockDetail.mockResolvedValue(CLOSED);
    mockDispute.mockRejectedValue(portalError(409002));
    renderDetail();
    await waitFor(() => expect(screen.getByText('提交异议')).toBeInTheDocument());

    fireEvent.change(screen.getByLabelText('异议理由'), { target: { value: LONG_REASON } });
    await userEvent.click(screen.getByText('提交异议'));

    await waitFor(() =>
      expect(vi.mocked(message.error)).toHaveBeenCalledWith(
        '该期对账单当前不可提异议 (期间未闭期 / 状态非 ADJUSTMENT_CREATED / 已异议过 / 无关联调账单)'
      )
    );
    expect(navigateMock).not.toHaveBeenCalled();
  });

  it('异议金额超范围 400_004 → 提示金额超范围', async () => {
    mockDetail.mockResolvedValue(CLOSED);
    mockDispute.mockRejectedValue(portalError(400004));
    renderDetail();
    await waitFor(() => expect(screen.getByText('提交异议')).toBeInTheDocument());

    fireEvent.change(screen.getByLabelText('异议理由'), { target: { value: OTHER_LONG_REASON } });
    await userEvent.click(screen.getByText('提交异议'));

    await waitFor(() =>
      expect(vi.mocked(message.error)).toHaveBeenCalledWith(
        '主张金额超出可表示范围 (最多 14 位整数 + 4 位小数)'
      )
    );
  });

  it('无权限 403 → 提示"没有提异议的权限" (重试无用)', async () => {
    mockDetail.mockResolvedValue(CLOSED);
    mockDispute.mockRejectedValue({ response: { status: 403, data: { code: 403003 } } });
    renderDetail();
    await waitFor(() => expect(screen.getByText('提交异议')).toBeInTheDocument());

    fireEvent.change(screen.getByLabelText('异议理由'), { target: { value: LONG_REASON } });
    await userEvent.click(screen.getByText('提交异议'));

    await waitFor(() =>
      expect(vi.mocked(message.error)).toHaveBeenCalledWith('您没有提异议的权限, 请联系管理员')
    );
  });

  it('提交成功 → 提示里显示中文状态而不是 PENDING_L1', async () => {
    mockDetail.mockResolvedValue(CLOSED);
    mockDispute.mockResolvedValue({
      reconId: 1, adjustmentId: 31, adjustmentStatus: 'PENDING_L1', disputeAt: '2026-09-01T10:00:00',
    });
    renderDetail();
    await waitFor(() => expect(screen.getByText('提交异议')).toBeInTheDocument());

    fireEvent.change(screen.getByLabelText('异议理由'), { target: { value: OTHER_LONG_REASON } });
    await userEvent.click(screen.getByText('提交异议'));

    await waitFor(() =>
      expect(vi.mocked(message.success)).toHaveBeenCalledWith(
        '异议已提交, 对应调账单已进入 待 L1 审批'
      )
    );
  });

  it('主张金额超过 4 位小数 → 前端就舍入, 不会发 scale>4 的值给后端', async () => {
    mockDetail.mockResolvedValue(CLOSED);
    mockDispute.mockResolvedValue({
      reconId: 1, adjustmentId: 31, adjustmentStatus: 'PENDING_L1', disputeAt: '2026-09-01T10:00:00',
    });
    renderDetail();
    await waitFor(() => expect(screen.getByText('提交异议')).toBeInTheDocument());

    // 用 fireEvent.change 而不是 userEvent.type: CJK 逐字派发 key 事件太慢,
    // 全量并行跑时会撞 5 秒 per-test 超时。
    fireEvent.change(screen.getByLabelText('异议理由'), { target: { value: OTHER_LONG_REASON } });

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
    const RECON_1: ReconView = { ...CLOSED, id: 1, period: '2026-07', expectedAmount: 100 };
    const RECON_2: ReconView = { ...CLOSED, id: 2, period: '2026-08', expectedAmount: 999 };
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
