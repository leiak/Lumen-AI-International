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

    // ProTable's `request` is fired async on mount. Wait for the mocked
    // service call to land first — this both confirms the request fired
    // AND guarantees ProTable has begun rendering with the response.
    // The toolbar 期间 Select is initialized to the current month
    // (2026-10 at test time) so `getByText('2026-10')` alone cannot
    // distinguish the toolbar option from the row cell — both render
    // the same text. Wait for the row's variance column ("50.00")
    // instead, which is unique to the mocked row and proves the row
    // populated after the request resolved.
    await waitFor(() => {
      expect(financeReconciliationService.list).toHaveBeenCalled();
    });
    await waitFor(() => {
      expect(screen.getByText('50.00')).toBeInTheDocument();
    });
  });
});