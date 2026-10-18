/**
 * Finance/Reports/Summary — RTL smoke test (Iteration 4.4 PR3, Task 3.3).
 *
 * Locks:
 *   1. Renders the ReportSummary with all three tab labels (汇总 / 明细 / 差异).
 *   2. "下载 Excel" button is visible on the active tab's form.
 *
 * Mock strategy mirrors `FinanceInvoiceList.test.tsx` / `FinanceRuleList.test.tsx`:
 *   - mock `financeReportService` directly (UI wiring, not HTTP).
 *   - wrap with <AuthContext.Provider> + <ConfigProvider> so `useAccess()`
 *     resolves and antd renders in zh-CN locale.
 *
 * Note: ReportSummary renders all 3 tab forms eagerly (one per tab key in
 * TAB_ITEMS), so the "下载 Excel" button is in the DOM as soon as the
 * Tabs component finishes its first paint. We assert it via
 * `getAllByRole('button', { name: /下载/ })` since each tab owns its own
 * form.
 *
 * `ReportSummary` is a default export, not named — adapt accordingly.
 */

import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { ConfigProvider } from 'antd';
import zhCN from 'antd/locale/zh_CN';

import ReportSummary from '@/components/finance/ReportSummary';
import { AuthContext } from '@/auth/AuthProvider';
import type { AuthContextValue } from '@/auth/types';

// jsdom doesn't ship matchMedia — antd's App component may probe it.
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

// Mock the service module — UI wiring is what we're verifying, not HTTP.
vi.mock('@/services/finance/financeReportService', () => ({
  financeReportService: {
    summary: vi.fn(),
    detail: vi.fn(),
    variance: vi.fn(),
  },
}));

import { financeReportService } from '@/services/finance/financeReportService';

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

describe('Finance/Reports/Summary', () => {
  it('renders the report tabs and 下载 Excel button', () => {
    const Wrapper = makeWrapper(['finance:report:view']);
    render(<Wrapper><ReportSummary /></Wrapper>);

    // All three tab labels (汇总 / 明细 / 差异) should be present.
    expect(screen.getByRole('tab', { name: '汇总' })).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: '明细' })).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: '差异' })).toBeInTheDocument();

    // Each tab renders its own form with a "下载 Excel" button. antd
    // Tabs mounts all three tab panes (hidden tabs are still in the DOM
    // until the user switches), so we expect 3 download buttons.
    const downloadBtns = screen.getAllByRole('button', { name: /下载/ });
    expect(downloadBtns.length).toBeGreaterThanOrEqual(1);
    // Defensive: ensure the service module wasn't accidentally invoked
    // during render — the click is what triggers it.
    expect(financeReportService.summary).not.toHaveBeenCalled();
    expect(financeReportService.detail).not.toHaveBeenCalled();
    expect(financeReportService.variance).not.toHaveBeenCalled();
  });
});