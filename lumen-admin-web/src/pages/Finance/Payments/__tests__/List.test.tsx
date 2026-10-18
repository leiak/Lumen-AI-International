/**
 * Finance/Payments/List — RTL smoke test (Iteration 4.4 PR3, Task 3.3).
 *
 * Locks:
 *   1. Renders the PaymentRecordList with a mocked payment-record row.
 *   2. Period text "2026-10" appears in the document.
 *
 * Mock strategy mirrors `FinanceInvoiceList.test.tsx` / `FinanceRuleList.test.tsx`:
 *   - mock `financePaymentService` directly (UI wiring, not HTTP).
 *   - wrap with <AuthContext.Provider> + <ConfigProvider> so `useAccess()`
 *     resolves and antd renders in zh-CN locale.
 *
 * `PaymentRecordList` is a default export, not named — adapt accordingly.
 */

import { describe, it, expect, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { ConfigProvider } from 'antd';
import zhCN from 'antd/locale/zh_CN';

import PaymentRecordList from '@/components/finance/PaymentRecordList';
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
vi.mock('@/services/finance/financePaymentService', () => ({
  financePaymentService: {
    list: vi.fn(),
    get: vi.fn(),
    create: vi.fn(),
    importCsv: vi.fn(),
  },
}));

import { financePaymentService } from '@/services/finance/financePaymentService';
import type { BillingPaymentRecord } from '@/services/finance/financePaymentService';

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

const basePayment: BillingPaymentRecord = {
  id: 1,
  period: '2026-10',
  direction: 'RECEIVABLE',
  counterpartyId: 42,
  counterpartyType: 'CUSTOMER',
  currency: 'CNY',
  amount: 1234.56,
  paymentDate: '2026-10-15',
  source: 'MANUAL',
};

function asList(rows: BillingPaymentRecord[]) {
  return { records: rows, total: rows.length };
}

describe('Finance/Payments/List', () => {
  it('renders payment-record list with mocked row', async () => {
    vi.mocked(financePaymentService.list).mockResolvedValue(asList([basePayment]));

    const Wrapper = makeWrapper([
      'finance:payment:view',
      'finance:payment:edit',
    ]);
    render(<Wrapper><PaymentRecordList /></Wrapper>);

    // ProTable's `request` is fired async on mount; wait for the row to
    // populate before asserting on the rendered DOM.
    await waitFor(() => {
      expect(screen.getByText('2026-10')).toBeInTheDocument();
    });
    // The mocked service was invoked at least once by ProTable.
    expect(financePaymentService.list).toHaveBeenCalled();
  });
});