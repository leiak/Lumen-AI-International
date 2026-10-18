/**
 * Finance/Reconciliation/List — RTL smoke test (Iteration 4.4 PR3, Task 3.3).
 *
 * Locks:
 *   1. Renders the ReconciliationList with a mocked reconciliation row.
 *   2. Period text "2026-10" appears in the document.
 *
 * Mock strategy mirrors `FinanceInvoiceList.test.tsx` / `FinanceRuleList.test.tsx`:
 *   - mock `financeReconciliationService` directly (UI wiring, not HTTP).
 *   - wrap with <AuthContext.Provider> + <ConfigProvider> so `useAccess()`
 *     resolves and antd renders in zh-CN locale.
 *
 * `ReconciliationList` is a default export, not named — adapt accordingly.
 */

import { describe, it, expect, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { ConfigProvider } from 'antd';
import zhCN from 'antd/locale/zh_CN';

import ReconciliationList from '@/components/finance/ReconciliationList';
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
vi.mock('@/services/finance/financeReconciliationService', () => ({
  financeReconciliationService: {
    list: vi.fn(),
    run: vi.fn(),
    resolve: vi.fn(),
    waive: vi.fn(),
  },
}));

import { financeReconciliationService } from '@/services/finance/financeReconciliationService';
import type { BillingReconciliation } from '@/services/finance/financeReconciliationService';

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

const baseReconciliation: BillingReconciliation = {
  id: 1,
  period: '2026-10',
  direction: 'RECEIVABLE',
  counterpartyId: 42,
  counterpartyType: 'CUSTOMER',
  currency: 'CNY',
  expectedAmount: 1000,
  actualAmount: 950,
  varianceAmount: 50,
  reconciliationStatus: 'ADJUSTMENT_CREATED',
};

function asList(rows: BillingReconciliation[]) {
  return { records: rows, total: rows.length };
}

describe('Finance/Reconciliation/List', () => {
  it('renders reconciliation list with mocked row', async () => {
    vi.mocked(financeReconciliationService.list).mockResolvedValue(
      asList([baseReconciliation]),
    );

    const Wrapper = makeWrapper([
      'finance:reconciliation:view',
      'finance:reconciliation:run',
      'finance:reconciliation:resolve',
      'finance:reconciliation:waive',
    ]);
    render(<Wrapper><ReconciliationList /></Wrapper>);

    // ProTable's `request` is fired async on mount; wait for the row to
    // populate before asserting on the rendered DOM. Note: the toolbar
    // also renders a 期间 Select whose options include "2026-10", but
    // ProTable's request function relies on the filterPeriod state, so
    // the row's period cell is what populates after the mocked list
    // resolves.
    await waitFor(() => {
      expect(screen.getByText('2026-10')).toBeInTheDocument();
    });
    // The mocked service was invoked at least once by ProTable.
    expect(financeReconciliationService.list).toHaveBeenCalled();
  });
});