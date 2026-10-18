/**
 * Finance/Closing/List — RTL smoke test (Iteration 4.4 PR3, Task 3.3).
 *
 * Locks:
 *   1. Renders the ClosingList with a mocked monthly-closing row.
 *   2. Period text "2026-10" appears in the document.
 *   3. "开始月结" action button is visible for OPEN-status rows.
 *
 * Mock strategy mirrors `FinanceInvoiceList.test.tsx` / `FinanceRuleList.test.tsx`:
 *   - mock `financeClosingService` directly (UI wiring, not HTTP).
 *   - wrap with <AuthContext.Provider> + <ConfigProvider> so `useAccess()`
 *     resolves and antd renders in zh-CN locale.
 *
 * Notes vs. the plan example:
 *   - The plan's `vi.mock('@/services/finance/financeClosingService')` and
 *     `{ data: [...] }` shape don't match this codebase. We use the actual
 *     `{ records, total }` shape that `financeClosingService.list`
 *     returns and supply all required service method stubs up-front
 *     (Vitest's auto-mock would emit defaults that don't satisfy the
 *     `Promise<X>` return-type contract for ProTable's request adapter).
 *   - `ClosingList` is a default export, not named — adapt accordingly.
 */

import { describe, it, expect, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { ConfigProvider } from 'antd';
import zhCN from 'antd/locale/zh_CN';

import ClosingList from '@/components/finance/ClosingList';
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
// Polyfill to return an empty value so the measurement is a no-op.
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

// Mock the service module — UI wiring is what we're verifying, not HTTP.
vi.mock('@/services/finance/financeClosingService', () => ({
  financeClosingService: {
    list: vi.fn(),
    get: vi.fn(),
    start: vi.fn(),
    close: vi.fn(),
    reopen: vi.fn(),
  },
}));

import { financeClosingService } from '@/services/finance/financeClosingService';
import type { BillingMonthlyClosing } from '@/services/finance/financeClosingService';

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

const baseClosing: BillingMonthlyClosing = {
  id: 1,
  period: '2026-10',
  status: 'OPEN',
};

function asList(rows: BillingMonthlyClosing[]) {
  return { records: rows, total: rows.length };
}

describe('Finance/Closing/List', () => {
  it('renders closing list with mocked row and 开始月结 button', async () => {
    vi.mocked(financeClosingService.list).mockResolvedValue(asList([baseClosing]));

    const Wrapper = makeWrapper([
      'finance:closing:view',
      'finance:closing:manage',
    ]);
    render(<Wrapper><ClosingList /></Wrapper>);

    // ProTable's `request` is fired async on mount; wait for the row to
    // populate before asserting on the rendered DOM.
    await waitFor(() => {
      expect(screen.getByText('2026-10')).toBeInTheDocument();
    });
    // OPEN-status row should expose 开始月结 (per the component's render
    // switch in ClosingList.tsx).
    expect(screen.getByText('开始月结')).toBeInTheDocument();
    // And the mocked service was invoked at least once by ProTable.
    expect(financeClosingService.list).toHaveBeenCalled();
  });
});