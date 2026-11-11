/**
 * pages/Order/Detail — RTL smoke test (Iteration 4.6, Task 9).
 *
 * 锁三件事:
 *
 *   1. **动作按钮由后端 `allowedNextStates` 驱动** —— 前端不自己判断状态机。
 *      allowedNextStates 含 CONFIRMED → 渲染「确认」; 不含 → 不渲染。
 *      这条是本页面最核心的约定, 必须锁死。
 *   2. **需要原因的动作弹原因框, 不需要原因的动作不弹** ——
 *      退回 / 取消 / 作废 / 关闭 要原因; 提交 / 确认 / 开始操作 / 交付 / 结算 不要。
 *   3. 渲染「含危险品」项与「流转记录」卡片。
 *
 * Mock 写法照 `src/pages/Finance/Closing/__tests__/List.test.tsx`:
 *   - 直接 mock `orderService` (测 UI 接线, 不测 HTTP);
 *   - 用 <AuthContext.Provider> + <ConfigProvider> + antd <App> 包一层
 *     (useAccess 需要 AuthContext; App.useApp() 需要 App context, 否则
 *      message 是空对象, catch 分支里一调就炸);
 *   - 用 <MemoryRouter> + <Routes> 提供 :id 路由参数。
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent, within } from '@testing-library/react';
import { App, ConfigProvider } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import { MemoryRouter, Route, Routes } from 'react-router-dom';

import OrderDetailPage from '@/pages/Order/Detail';
import { AuthContext } from '@/auth/AuthProvider';
import type { AuthContextValue } from '@/auth/types';

// jsdom doesn't ship matchMedia — antd 的 responsive 组件需要它。
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

// jsdom's getComputedStyle throws "Not implemented" when a pseudoElt is passed.
// rc-table's scrollbar measurement asks for ':after' etc.
if (typeof window !== 'undefined') {
  const originalGetComputedStyle = window.getComputedStyle.bind(window);
  window.getComputedStyle = ((elt: Element, pseudoElt?: string | null) => {
    if (pseudoElt) {
      return {
        getPropertyValue: () => '',
        width: '0px',
        height: '0px',
      } as unknown as CSSStyleDeclaration;
    }
    return originalGetComputedStyle(elt);
  }) as typeof window.getComputedStyle;
}

// 只把 orderService 这个对象换成 mock —— 同模块的其它导出保持真实。
vi.mock('@/services/order/orderService', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/services/order/orderService')>();
  return {
    ...actual,
    orderService: {
      page: vi.fn(),
      get: vi.fn(),
      create: vi.fn(),
      update: vi.fn(),
      submit: vi.fn(),
      confirm: vi.fn(),
      returnToDraft: vi.fn(),
      cancel: vi.fn(),
      voidOrder: vi.fn(),
      startOperation: vi.fn(),
      deliver: vi.fn(),
      settle: vi.fn(),
      close: vi.fn(),
    },
  };
});

import { orderService } from '@/services/order/orderService';
import type { OrderDetail } from '@/services/order/orderService';

function makeWrapper(perms: string[] = []): React.FC<{ children: React.ReactNode }> {
  const user = perms.length > 0
    ? { userId: 1, tenantId: 1, username: 'tester', roles: ['ORDER_SALES'], perms }
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
      <ConfigProvider locale={zhCN}>
        <App>
          <MemoryRouter initialEntries={['/orders/7']}>
            <Routes>
              <Route path="/orders/:id" element={<>{children}</>} />
            </Routes>
          </MemoryRouter>
        </App>
      </ConfigProvider>
    </AuthContext.Provider>
  );
}

function detail(allowedNextStates: string[]): OrderDetail {
  return {
    id: 7,
    orderNo: 'ORD-20261111-0001',
    customerId: 42,
    businessType: 'OCEAN_FCL',
    tradeDirection: 'IMPORT',
    incoterms: 'FOB',
    serviceType: 'D2D',
    originAddress: '上海',
    destAddress: '洛杉矶',
    totalQty: 100,
    totalWeightKg: 250,
    totalVolumeCbm: 1.5,
    orderStatus: 'PENDING_CONFIRM',
    settlementCurrency: 'CNY',
    etd: '2026-11-20',
    eta: '2026-12-05',
    createdAt: '2026-11-11T08:00:00',
    cargoReadyDate: '2026-11-15',
    containerQty: 1,
    containerTypeCode: '40HQ',
    marksAndNos: 'N/M',
    cargoDescription: '陶瓷杯',
    isDangerous: 1,
    isOversized: 0,
    temperatureRange: null,
    remark: null,
    cancelReason: null,
    closeReason: null,
    voidReason: null,
    submittedAt: '2026-11-11T09:00:00',
    confirmedAt: null,
    deliveredAt: null,
    settledAt: null,
    closedAt: null,
    createdBy: 3,
    updatedBy: null,
    cargo: [],
    serviceItems: [],
    allowedNextStates,
  };
}

beforeEach(() => {
  vi.clearAllMocks();
});

describe('pages/Order/Detail', () => {
  it('allowedNextStates 含 CONFIRMED → 渲染「确认」按钮', async () => {
    vi.mocked(orderService.get).mockResolvedValue(
      detail(['CONFIRMED', 'DRAFT', 'CANCELLED', 'VOID']),
    );

    const Wrapper = makeWrapper(['order:confirm', 'order:submit', 'order:cancel', 'order:void']);
    render(<Wrapper><OrderDetailPage /></Wrapper>);

    await waitFor(() => {
      expect(screen.getByText('ORD-20261111-0001')).toBeInTheDocument();
    });
    // antd 的 Button 会给两个汉字之间插空格 (autoInsertSpaceInButton),
    // 所以可访问名是「确 认」, 用正则匹配。
    expect(screen.getByRole('button', { name: /确\s*认/ })).toBeInTheDocument();
  }, 20000);

  it('allowedNextStates 不含 CONFIRMED → 不渲染「确认」按钮', async () => {
    vi.mocked(orderService.get).mockResolvedValue(
      detail(['PENDING_CONFIRM', 'CANCELLED']),
    );

    const Wrapper = makeWrapper(['order:confirm', 'order:submit', 'order:cancel', 'order:void']);
    render(<Wrapper><OrderDetailPage /></Wrapper>);

    await waitFor(() => {
      expect(screen.getByText('ORD-20261111-0001')).toBeInTheDocument();
    });
    expect(screen.queryByRole('button', { name: /确\s*认/ })).toBeNull();
    // 但 allowedNextStates 里有的 PENDING_CONFIRM 对应的「提交」必须在。
    expect(screen.getByRole('button', { name: /提\s*交/ })).toBeInTheDocument();
  }, 20000);

  it('需要原因的动作弹原因框, 不需要原因的动作直接执行且不弹框', async () => {
    vi.mocked(orderService.get).mockResolvedValue(
      detail(['PENDING_CONFIRM', 'CANCELLED']),
    );
    vi.mocked(orderService.submit).mockResolvedValue(detail(['PENDING_CONFIRM']));

    const Wrapper = makeWrapper(['order:submit', 'order:cancel']);
    render(<Wrapper><OrderDetailPage /></Wrapper>);

    await waitFor(() => {
      expect(screen.getByText('ORD-20261111-0001')).toBeInTheDocument();
    });

    // 不需要原因的动作 (提交) —— 直接调后端, 不弹框。
    fireEvent.click(screen.getByRole('button', { name: /提\s*交/ }));
    await waitFor(() => {
      expect(orderService.submit).toHaveBeenCalledWith(7);
    });
    expect(screen.queryByText(/请填写原因/)).toBeNull();

    // 需要原因的动作 (取消) —— 弹框。
    fireEvent.click(screen.getByRole('button', { name: /取\s*消/ }));
    await waitFor(() => {
      expect(screen.getByText(/请填写原因/)).toBeInTheDocument();
    });
    // 框的标题带上动作名。
    expect(screen.getByText(/取\s*消\s*—\s*请填写原因/)).toBeInTheDocument();
    // 只弹了框, 还没调后端。
    expect(orderService.cancel).not.toHaveBeenCalled();
  }, 20000);

  it('渲染「含危险品」项与「流转记录」卡片', async () => {
    vi.mocked(orderService.get).mockResolvedValue(detail([]));

    const Wrapper = makeWrapper();
    render(<Wrapper><OrderDetailPage /></Wrapper>);

    await waitFor(() => {
      expect(screen.getByText('ORD-20261111-0001')).toBeInTheDocument();
    });

    // 「含危险品」= true (isDangerous 响应侧是 Integer 1) → 显示「是」。
    // Descriptions 用的是老式 <Descriptions.Item> 子节点 API, 渲染成
    // <th class="ant-descriptions-item-label"> + 相邻的 <td>。
    const labelCell = screen.getByText('含危险品').closest('th');
    expect(labelCell?.nextElementSibling?.textContent).toBe('是');

    // 「流转记录」卡片 + 提交时间 (DateTimeColumn 格式化成 'YYYY-MM-DD HH:mm:ss')。
    expect(screen.getByText('流转记录')).toBeInTheDocument();
    expect(screen.getByText('2026-11-11 09:00:00')).toBeInTheDocument();
  }, 20000);

  it('九个动作逐个验: 退回/取消/作废/关闭归档弹原因框, 其余五个不弹', async () => {
    // 9 个目标状态全放开 + 9 个动作的权限全给 → 每个按钮都渲染出来。
    const allStates = [
      'PENDING_CONFIRM',
      'CONFIRMED',
      'DRAFT',
      'IN_OPERATION',
      'DELIVERED',
      'SETTLED',
      'CLOSED',
      'CANCELLED',
      'VOID',
    ];
    vi.mocked(orderService.get).mockResolvedValue(detail(allStates));

    const Wrapper = makeWrapper([
      'order:submit',
      'order:confirm',
      'order:operate',
      'order:settle',
      'order:close',
      'order:cancel',
      'order:void',
    ]);
    render(<Wrapper><OrderDetailPage /></Wrapper>);

    await waitFor(() => {
      expect(screen.getByText('ORD-20261111-0001')).toBeInTheDocument();
    });

    // 动作标签 → (是否需要填原因, 不需要原因时调哪个 service 方法)
    // (与 Detail.tsx 的 ACTIONS 表一一对应)。
    const ACTIONS_UNDER_TEST: {
      label: string;
      needReason: boolean;
      method?: 'submit' | 'confirm' | 'startOperation' | 'deliver' | 'settle';
    }[] = [
      { label: '提交', needReason: false, method: 'submit' },
      { label: '确认', needReason: false, method: 'confirm' },
      { label: '退回', needReason: true },
      { label: '开始操作', needReason: false, method: 'startOperation' },
      { label: '交付', needReason: false, method: 'deliver' },
      { label: '结算', needReason: false, method: 'settle' },
      { label: '关闭归档', needReason: true },
      { label: '取消', needReason: true },
      { label: '作废', needReason: true },
    ];

    for (const { label, needReason, method } of ACTIONS_UNDER_TEST) {
      // antd 的 Button 在两个汉字之间插空格 → 可访问名是「提 交」这种。
      const nameRe = new RegExp(label.split('').join('\\s*'));
      const btn = await waitFor(() => screen.getByRole('button', { name: nameRe }));

      fireEvent.click(btn);

      if (needReason) {
        // 需要原因 → 弹框, 且后端还没被调用。
        await waitFor(() => {
          expect(screen.getByRole('dialog')).toBeInTheDocument();
        });
        expect(screen.getByText(/请填写原因/)).toBeInTheDocument();
        // 关掉框, 好试下一个动作。
        fireEvent.click(
          within(screen.getByRole('dialog')).getByRole('button', { name: /取\s*消/ }),
        );
        await waitFor(() => {
          expect(screen.queryByRole('dialog')).toBeNull();
        });
      } else {
        // 不需要原因 → 不弹框, 直接调后端。
        await waitFor(() => {
          expect(screen.queryByRole('dialog')).toBeNull();
        });
        await waitFor(() => {
          expect(orderService[method!]).toHaveBeenCalledWith(7);
        });
      }
    }

    // 四个带原因的动作一次都没打到后端 —— 只弹框, 等人填原因。
    expect(orderService.cancel).not.toHaveBeenCalled();
    expect(orderService.voidOrder).not.toHaveBeenCalled();
    expect(orderService.close).not.toHaveBeenCalled();
    expect(orderService.returnToDraft).not.toHaveBeenCalled();
  }, 60000);
});
