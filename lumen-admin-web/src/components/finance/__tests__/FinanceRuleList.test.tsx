/**
 * FinanceRuleList — RTL smoke + interaction tests (Iteration 4.3.1 PR4).
 *
 * Locks:
 *   1. Renders the table with mocked rule rows.
 *   2. Clicking 启用 invokes financeRuleService.activate.
 *
 * The service module is mocked directly — UI wiring is what we're
 * verifying, not HTTP. The HTTP layer has dedicated service tests.
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ConfigProvider } from 'antd';
import zhCN from 'antd/locale/zh_CN';

import FinanceRuleList from '@/components/finance/FinanceRuleList';
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
// passed. Polyfill so rc-table's scrollbar measurement is a no-op.
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

vi.mock('@/services/finance/financeRuleService', () => ({
  financeRuleService: {
    list: vi.fn(),
    get: vi.fn(),
    create: vi.fn(),
    update: vi.fn(),
    activate: vi.fn(),
    disable: vi.fn(),
  },
}));

import { financeRuleService } from '@/services/finance/financeRuleService';
import type { BillingRule } from '@/services/finance/financeRuleService';

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

const baseRule: BillingRule = {
  id: 11,
  businessType: 'LAST_MILE',
  direction: 'RECEIVABLE',
  counterpartyType: 'CUSTOMER',
  status: 'DRAFT',
  priority: 100,
};

function asList(rows: BillingRule[]) {
  return { records: rows, total: rows.length };
}

describe('FinanceRuleList', () => {
  beforeEach(() => {
    vi.mocked(financeRuleService.list).mockReset();
    vi.mocked(financeRuleService.activate)
      .mockReset()
      .mockResolvedValue({ ...baseRule, status: 'ACTIVE' });
  });

  it('renders the table with mocked rules', async () => {
    vi.mocked(financeRuleService.list).mockResolvedValue(
      asList([
        baseRule,
        {
          ...baseRule,
          id: 22,
          businessType: 'WAREHOUSE_IN',
          direction: 'PAYABLE',
          counterpartyType: 'WAREHOUSE',
          status: 'ACTIVE',
        },
      ]),
    );

    const Wrapper = makeWrapper(['finance:rule:view']);
    render(<Wrapper><FinanceRuleList /></Wrapper>);

    await waitFor(() => {
      expect(financeRuleService.list).toHaveBeenCalled();
    });
    // Wait for the row's status tag (DRAFT/启用) — that surface is
    // plain text in the cell, while businessType is rendered through
    // a select-filter widget whose DOM contains both the option label
    // and the raw value, making it an unstable selector.
    await waitFor(() => {
      expect(screen.getByText('草稿')).toBeInTheDocument();
    });
    // '启用' appears twice in the rendered DOM: as a STATUS_OPTIONS
    // filter option AND as the row's action button (visible but disabled
    // because the user only has view perms). Use getAllByText to assert
    // at least one match — both surfaces are intentional.
    expect(screen.getAllByText('启用').length).toBeGreaterThan(0);
  });

  it('clicking 启用 calls financeRuleService.activate', async () => {
    vi.mocked(financeRuleService.list).mockResolvedValue(asList([baseRule]));

    const Wrapper = makeWrapper(['finance:rule:view', 'finance:rule:edit']);
    render(<Wrapper><FinanceRuleList /></Wrapper>);

    // Wait for the row to render first (status tag), then look for
    // the activate action button.
    await waitFor(() => {
      expect(screen.getByText('草稿')).toBeInTheDocument();
    });
    const activateBtn = await waitFor(() =>
      screen.getByRole('button', { name: /启.?用/ }),
    );
    const user = userEvent.setup();
    await user.click(activateBtn);

    await waitFor(() => {
      expect(financeRuleService.activate).toHaveBeenCalledWith(11);
    });
  });
});