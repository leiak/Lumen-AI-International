/**
 * FinanceAdjustmentList — 商家异议可见性 (Iteration 4.5C-2.1, 缺口 #13).
 *
 * <p>商家提异议后原话写在 {@code t_reconciliation} 上, 而审批界面展示的是
 * {@code runReconciliation} 自动生成的「对账差异自动生成: …」—— 商家在争什么,
 * 财务根本看不到。这组用例锁住 admin 侧能看到的那三样东西: 有没有异议 /
 * 商家主张的金额 / 商家的原话。
 *
 * <p>骨架照抄 {@link FinanceInvoiceList.test.tsx} (matchMedia polyfill /
 * getComputedStyle polyfill / AuthContext.Provider / ConfigProvider locale),
 * 不另发明一套 —— 那边的 mock 方式已经被这个仓库验证过了。
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { ConfigProvider } from 'antd';
import zhCN from 'antd/locale/zh_CN';

import FinanceAdjustmentList, {
  DISPUTED_FILTER_OPTIONS,
  toDisputedParam,
} from '@/components/finance/FinanceAdjustmentList';
import { AuthContext } from '@/auth/AuthProvider';
import type { AuthContextValue } from '@/auth/types';

// jsdom doesn't ship matchMedia — ProTable's responsive observer needs it.
if (typeof window !== 'undefined' && !window.matchMedia) {
  Object.defineProperty(window, 'matchMedia', {
    writable: true,
    value: vi.fn().mockImplementation((query: string) => ({
      matches: false,
      media: query,
      onchange: null,
      addListener: vi.fn(),
      removeListener: vi.fn(),
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      dispatchEvent: vi.fn(),
    })),
  });
}

// jsdom's getComputedStyle throws "Not implemented" when a pseudoElt is
// passed. rc-table's scrollbar measurement asks for ':after' etc.
if (typeof window !== 'undefined') {
  const originalGetComputedStyle = window.getComputedStyle.bind(window);
  window.getComputedStyle = ((elt: Element, pseudoElt?: string | null) => {
    if (pseudoElt) {
      return {
        getPropertyValue: () => '',
        // Bare-minimum surface used by rc-table's measureScrollbarSize.
        width: '0px',
        height: '0px',
      } as unknown as CSSStyleDeclaration;
    }
    return originalGetComputedStyle(elt);
  }) as typeof window.getComputedStyle;
}

// Mock the service module — the test focuses on UI wiring, not HTTP.
vi.mock('@/services/finance/financeAdjustmentService', () => ({
  financeAdjustmentService: {
    listAdjustments: vi.fn(),
    getAdjustment: vi.fn(),
    createAdjustment: vi.fn(),
    submitAdjustment: vi.fn(),
    approveAdjustmentL1: vi.fn(),
    rejectAdjustmentL1: vi.fn(),
    approveAdjustmentL2: vi.fn(),
    rejectAdjustmentL2: vi.fn(),
    markAdjustmentPosted: vi.fn(),
  },
}));

import { financeAdjustmentService } from '@/services/finance/financeAdjustmentService';
import type { BillingAdjustment } from '@/types/finance';

function makeWrapper(perms: string[] = []): React.FC<{ children: React.ReactNode }> {
  const user = perms.length > 0
    ? { userId: 1, tenantId: 1, username: 'tester', roles: ['FINANCE_OPS'], perms }
    : null;
  const value: AuthContextValue = {
    user,
    isAuthenticated: !!user,
    isLoading: false,
    login: async () => {},
    logout: () => {},
    refresh: async () => 'token',
    setUser: () => {},
  };
  return ({ children }) => (
    <AuthContext.Provider value={value}>
      <ConfigProvider locale={zhCN}>{children}</ConfigProvider>
    </AuthContext.Provider>
  );
}

/** 有异议且**主张了金额**的调账单。 */
const DISPUTED: BillingAdjustment = {
  id: 31,
  adjustmentNo: 'ADJ-1-31',
  sourceInvoiceId: 21,
  adjustmentType: 'CREDIT',
  amount: 100,
  currency: 'CNY',
  fxRateAtCreation: 1,
  taxRate: 0.13,
  grossAmount: 113,
  taxAmount: 13,
  netAmount: 100,
  reason: '对账差异自动生成: 期间 2026-07 差异 -10.00 CNY',
  status: 'PENDING_L1',
  disputeReason: '9 月 3 日那票没送到',
  disputeAmount: -12,
  disputeAt: '2026-09-01T10:00:00',
};

/**
 * 有异议但**只质疑不主张金额** —— {@code dispute_amount} 为 NULL 是 V39 的正常业务值
 * (商家能提异议, 但说不清差在哪, 就只记个理由)。
 */
const DISPUTED_NO_AMOUNT: BillingAdjustment = {
  ...DISPUTED,
  id: 32,
  adjustmentNo: 'ADJ-1-32',
  disputeAmount: null,
  disputeReason: '金额对不上, 但我说不清差在哪',
};

/** 没有任何商家异议的调账单 —— 「商家异议」列应显示「无」。 */
const CLEAN: BillingAdjustment = {
  ...DISPUTED,
  id: 33,
  adjustmentNo: 'ADJ-1-33',
  disputeReason: null,
  disputeAmount: null,
  disputeAt: null,
};

/**
 * 取抽屉里某个 Descriptions 行的完整文本 (标签 + 值)。
 *
 * <p>只断言 label 是不够的 —— label 是无条件渲染的, 把值改成恒 '—' 断言照样过。
 * 这里按 label 定位到那一行再取整行文本, 值的任何变化都能被看见。
 */
function drawerRowText(label: string): string {
  return screen.getByText(label).closest('tr')?.textContent ?? '';
}

describe('FinanceAdjustmentList — 商家异议', () => {
  beforeEach(() => {
    vi.mocked(financeAdjustmentService.listAdjustments).mockReset();
    vi.mocked(financeAdjustmentService.getAdjustment).mockReset();
  });

  it('列表行显示"有异议"与主张金额', async () => {
    vi.mocked(financeAdjustmentService.listAdjustments).mockResolvedValue({
      records: [DISPUTED],
      total: 1,
    });

    const Wrapper = makeWrapper(['finance:adjustment:view']);
    render(<Wrapper><FinanceAdjustmentList /></Wrapper>);

    await waitFor(() => {
      expect(screen.getByText('ADJ-1-31')).toBeInTheDocument();
    });
    // formatCurrency(-12, 'CNY') → "¥-12.00"
    expect(screen.getByText(/有异议/)).toBeInTheDocument();
    expect(screen.getByText(/-12\.00/)).toBeInTheDocument();
  }, 15000);

  it('disputeAmount 为 null 时不崩 —— 只质疑不主张金额是正常业务值', async () => {
    vi.mocked(financeAdjustmentService.listAdjustments).mockResolvedValue({
      records: [DISPUTED_NO_AMOUNT],
      total: 1,
    });

    const Wrapper = makeWrapper(['finance:adjustment:view']);
    render(<Wrapper><FinanceAdjustmentList /></Wrapper>);

    await waitFor(() => {
      expect(screen.getByText('ADJ-1-32')).toBeInTheDocument();
    });
    // 关键: 这一行渲染出来了, 说明 formatCurrency 没吃到 null。
    // (null 传进 formatCurrency 会抛 TypeError → 整页白屏)
    expect(screen.getByText(/有异议/)).toBeInTheDocument();
  }, 15000);

  it('只质疑不主张金额时表格**不得**显示金额 —— 不能显示 ¥0.00', async () => {
    vi.mocked(financeAdjustmentService.listAdjustments).mockResolvedValue({
      records: [DISPUTED_NO_AMOUNT],
      total: 1,
    });

    const Wrapper = makeWrapper(['finance:adjustment:view']);
    render(<Wrapper><FinanceAdjustmentList /></Wrapper>);

    await waitFor(() => {
      expect(screen.getByText('ADJ-1-32')).toBeInTheDocument();
    });
    // Tag 的完整文本必须正好是「有异议」—— 不带任何金额。
    // 写成 `disputeAmount ?? 0` 会渲染成「有异议 ¥0.00」, 那是业务上明确无意义
    // 且误导的值 (后端 PortalReconController 明确拒收 claimedAmount == 0:
    // "0 是'既不认同也不主张' …… 只会让财务多一条没意义的审批单")。
    expect(screen.getByText(/^有异议$/)).toBeInTheDocument();
    expect(screen.queryByText(/¥0\.00/)).not.toBeInTheDocument();
  }, 15000);

  it('没有商家异议时显示「无」', async () => {
    vi.mocked(financeAdjustmentService.listAdjustments).mockResolvedValue({
      records: [CLEAN],
      total: 1,
    });

    const Wrapper = makeWrapper(['finance:adjustment:view']);
    render(<Wrapper><FinanceAdjustmentList /></Wrapper>);

    await waitFor(() => {
      expect(screen.getByText('ADJ-1-33')).toBeInTheDocument();
    });
    expect(screen.getByText('无')).toBeInTheDocument();
  }, 15000);

  it('抽屉里显示商家异议理由原文 / 主张金额 / 异议时间', async () => {
    vi.mocked(financeAdjustmentService.listAdjustments).mockResolvedValue({
      records: [DISPUTED],
      total: 1,
    });
    vi.mocked(financeAdjustmentService.getAdjustment).mockResolvedValue(DISPUTED);

    const Wrapper = makeWrapper(['finance:adjustment:view']);
    render(<Wrapper><FinanceAdjustmentList /></Wrapper>);

    await waitFor(() => {
      expect(screen.getByText('ADJ-1-31')).toBeInTheDocument();
    });
    // 抽屉靠「详情」按钮打开 (不是点行); 该按钮在缺 finance:adjustment:view
    // 权限时是 disabled 的 —— 权限清单里必须带上, 否则点不动且不报错。
    const detailBtn = await waitFor(() =>
      screen.getByRole('button', { name: /详.?情/ }),
    );
    fireEvent.click(detailBtn);

    await waitFor(() => {
      expect(drawerRowText('商家异议理由')).toContain('9 月 3 日那票没送到');
    });
    // 断言的是**值**不是 label —— label 是无条件渲染的, 只断言 label 的话
    // 把「异议时间」改成恒 '—' 这条用例照样绿。
    expect(drawerRowText('商家异议理由')).toContain('9 月 3 日那票没送到');
    expect(drawerRowText('商家主张金额')).toContain('¥-12.00');
    // DateTimeColumn 输出本地时区的 "yyyy-MM-dd HH:mm:ss"。
    // '2026-09-01T10:00:00' 无时区后缀 → 按本地时间解析 → 10:00:00。
    expect(drawerRowText('异议时间')).toContain('2026-09-01 10:00:00');
    // 超时要放宽: ProTable 首次挂载 + antd Drawer 的进出场动画在 jsdom 里
    // 要 5 秒上下, 默认的 5000ms 会让这条用例随机红。
  }, 20000);

  it('抽屉里 disputeAmount 为 null 时显示「未主张金额 (仅质疑)」而不是崩', async () => {
    vi.mocked(financeAdjustmentService.listAdjustments).mockResolvedValue({
      records: [DISPUTED_NO_AMOUNT],
      total: 1,
    });
    vi.mocked(financeAdjustmentService.getAdjustment).mockResolvedValue(DISPUTED_NO_AMOUNT);

    const Wrapper = makeWrapper(['finance:adjustment:view']);
    render(<Wrapper><FinanceAdjustmentList /></Wrapper>);

    await waitFor(() => {
      expect(screen.getByText('ADJ-1-32')).toBeInTheDocument();
    });
    const detailBtn = await waitFor(() =>
      screen.getByRole('button', { name: /详.?情/ }),
    );
    fireEvent.click(detailBtn);

    await waitFor(() => {
      expect(drawerRowText('商家主张金额')).toContain('未主张金额 (仅质疑)');
    });
  }, 20000);

  it('没有商家异议时抽屉里三项都显示「—」—— 不能是空白', async () => {
    vi.mocked(financeAdjustmentService.listAdjustments).mockResolvedValue({
      records: [CLEAN],
      total: 1,
    });
    vi.mocked(financeAdjustmentService.getAdjustment).mockResolvedValue(CLEAN);

    const Wrapper = makeWrapper(['finance:adjustment:view']);
    render(<Wrapper><FinanceAdjustmentList /></Wrapper>);

    await waitFor(() => {
      expect(screen.getByText('ADJ-1-33')).toBeInTheDocument();
    });
    const detailBtn = await waitFor(() =>
      screen.getByRole('button', { name: /详.?情/ }),
    );
    fireEvent.click(detailBtn);

    await waitFor(() => {
      expect(drawerRowText('商家异议理由')).toMatch(/^商家异议理由\s*—\s*$/);
      expect(drawerRowText('商家主张金额')).toMatch(/^商家主张金额\s*—\s*$/);
      expect(drawerRowText('异议时间')).toMatch(/^异议时间\s*—\s*$/);
    });
    // 「没有」和「渲染坏了」在财务眼里必须分得开 —— 所以是 '—' 而不是空白。
    // 断言锚住**整行** (标签 + 值), 只锚尾部的写法挡不住 "¥-12.00 —" 这类值。
  }, 20000);

  it('筛选下拉不选时 disputed 必须是 undefined —— false 会让默认就变成过滤', async () => {
    vi.mocked(financeAdjustmentService.listAdjustments).mockResolvedValue({
      records: [],
      total: 0,
    });

    const Wrapper = makeWrapper(['finance:adjustment:view']);
    render(<Wrapper><FinanceAdjustmentList /></Wrapper>);

    await waitFor(() => {
      expect(financeAdjustmentService.listAdjustments).toHaveBeenCalled();
    });
    // 不选筛选时 disputed 必须是 undefined (axios 会跳过 undefined),
    // 不能是 false —— 否则后端理解成"只回无异议的", 默认就变成过滤了。
    // 断言**每一次**调用, 不只是第一次 —— ProTable 将来若多发一次请求,
    // 只看 calls[0] 就锁错了对象。
    const calls = vi.mocked(financeAdjustmentService.listAdjustments).mock.calls;
    expect(calls.length).toBeGreaterThan(0);
    for (const call of calls) {
      expect(call[0].disputed).toBeUndefined();
    }
  }, 15000);

  it('搜索区「有无异议」到后端参数的映射: true / false / 不选 三条路', () => {
    // 单独抽出来单测, 因为 ProTable 的搜索下拉在 jsdom 里落在折叠区
    // (5 个搜索项超过默认折叠阈值), 驱动它的选中态很脆, 而这条映射
    // 恰恰是最容易写错的地方。
    expect(toDisputedParam('true')).toBe(true);
    expect(toDisputedParam('false')).toBe(false);
    // 不选 → undefined (不是 false!) —— 后端据此不过滤。
    expect(toDisputedParam(undefined)).toBeUndefined();
  });

  it('下拉的每个选项 value 都能映射成 boolean —— 接线不能断', () => {
    // 光测 toDisputedParam 不够: 它是直接喂字符串的, 绕开了
    // 「下拉的 option value ↔ 映射函数」这一段接线。把 value 写成布尔值,
    // 两个筛选项会**静默失效** (映射退化成 undefined → 后端不过滤 →
    // 列表照旧显示全部, 而用户以为自己筛过了)。
    for (const o of DISPUTED_FILTER_OPTIONS) {
      expect(typeof toDisputedParam(o.value)).toBe('boolean');
    }
    // 选项本身也不能少 —— 少了「无异议」这一项, 用户就没法筛无异议的。
    expect(DISPUTED_FILTER_OPTIONS.map((o) => o.value)).toEqual(['true', 'false']);
  });
});
