/**
 * pages/Order/List — RTL smoke test (Iteration 4.6, Task 9).
 *
 * 锁:
 *   1. 列表渲染出后端返回的行 (委托单号 + 状态标签)。
 *   2. ProTable 的搜索表单把 **orderStatus 列** 的取值传给 service 的 **status 参数**
 *      —— 列的 dataIndex 是 orderStatus, 后端 query 参数叫 status, 这一步改名
 *      是最容易写错的地方。
 *   3. 分页参数 current / size 照传。
 *
 * Mock 写法照 `src/pages/Finance/Closing/__tests__/List.test.tsx`:
 *   - 直接 mock `orderService` (测 UI 接线, 不测 HTTP);
 *   - 用 <AuthContext.Provider> + <ConfigProvider> + antd <App> 包一层
 *     (useAccess 需要 AuthContext; App.useApp() 需要 App context, 否则
 *      message 是空对象, catch 分支里一调就炸)。
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { App, ConfigProvider } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import { MemoryRouter } from 'react-router-dom';

import OrderList from '@/pages/Order/List';
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

// 只把 orderService 这个对象换成 mock —— 同模块里的受控词表常量
// (BUSINESS_TYPE_OPTIONS / ORDER_STATUS_OPTIONS) 是真实实现, 必须保留,
// 否则列表页渲染时拿不到下拉选项。
vi.mock('@/services/order/orderService', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/services/order/orderService')>();
  return {
    ...actual,
    orderService: {
      page: vi.fn(),
      get: vi.fn(),
      create: vi.fn(),
      update: vi.fn(),
    },
  };
});

import { orderService } from '@/services/order/orderService';
import type { OrderRow } from '@/services/order/orderService';

function makeWrapper(perms: string[] = ['order:view']): React.FC<{ children: React.ReactNode }> {
  const user = {
    userId: 1,
    tenantId: 1,
    username: 'tester',
    roles: ['ORDER_SALES'],
    perms,
  };
  const value: AuthContextValue = {
    user,
    isAuthenticated: true,
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
          <MemoryRouter>{children}</MemoryRouter>
        </App>
      </ConfigProvider>
    </AuthContext.Provider>
  );
}

const baseRow: OrderRow = {
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
  orderStatus: 'DRAFT',
  settlementCurrency: 'CNY',
  etd: '2026-11-20',
  eta: '2026-12-05',
  createdAt: '2026-11-11T09:00:00',
};

beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(orderService.page).mockResolvedValue({
    records: [baseRow],
    total: 1,
    pageNum: 1,
    pageSize: 20,
  });
});

describe('pages/Order/List', () => {
  it('渲染出后端返回的行 —— 委托单号与状态标签', async () => {
    const Wrapper = makeWrapper();
    render(<Wrapper><OrderList /></Wrapper>);

    await waitFor(() => {
      expect(screen.getByText('ORD-20261111-0001')).toBeInTheDocument();
    });
    // 状态列渲染的是 OrderStatusTag, 不是原始 code。
    expect(screen.getByText('草稿')).toBeInTheDocument();
    expect(orderService.page).toHaveBeenCalled();
  }, 20000);

  it('搜索表单的「状态」列值传给 service 的 status 参数, 并带上 current + size', async () => {
    const Wrapper = makeWrapper();
    const { container } = render(<Wrapper><OrderList /></Wrapper>);

    await waitFor(() => {
      expect(orderService.page).toHaveBeenCalled();
    });

    // 找到标题含「状态」的搜索表单项, 在里面找 Select 的下拉触发器。
    const items = Array.from(container.querySelectorAll('.ant-form-item'));
    const statusItem = items.find((el) => el.textContent?.includes('状态'));
    expect(statusItem).toBeDefined();
    const selector = (statusItem as HTMLElement).querySelector('.ant-select-selector');
    expect(selector).not.toBeNull();

    fireEvent.mouseDown(selector as Element);
    const option = await screen.findByTitle('已归档');
    fireEvent.click(option);

    // ProTable 的搜索表单不会自动提交 —— 要点「查询」。
    fireEvent.click(screen.getByRole('button', { name: /查\s*询/ }));

    await waitFor(() => {
      expect(orderService.page).toHaveBeenCalledWith(
        expect.objectContaining({ current: 1, size: 20, status: 'CLOSED' }),
      );
    });
  }, 20000);
});
