/**
 * FinanceInvoiceList — RTL smoke + interaction tests (Iteration 4.3.1 PR4).
 *
 * Locks:
 *   1. Renders the header + table body with mocked invoice rows.
 *   2. Shows 通过 / 驳回 buttons only when status = PENDING_L1.
 *   3. Clicking 通过 invokes financeInvoiceService.l1Approve.
 *   4. Status tag color matches the plan (DRAFT=default, PENDING_L1=gold,
 *      L1_APPROVED=green, PUSHED=blue, REJECTED=red).
 *
 * The test mocks `financeInvoiceService` directly (not MSW) — the
 * component-level wiring is what we're verifying, not the HTTP layer
 * (which has its own service tests).
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, within, fireEvent } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ConfigProvider } from 'antd';
import zhCN from 'antd/locale/zh_CN';

import FinanceInvoiceList from '@/components/finance/FinanceInvoiceList';
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
        // Bare-minimum surface used by rc-table's measureScrollbarSize.
        width: '0px',
        height: '0px',
      } as unknown as CSSStyleDeclaration;
    }
    return originalGetComputedStyle(elt);
  }) as typeof window.getComputedStyle;
}

// Mock the service module — the test focuses on UI wiring, not HTTP.
vi.mock('@/services/finance/financeInvoiceService', () => ({
  financeInvoiceService: {
    list: vi.fn(),
    get: vi.fn(),
    submit: vi.fn(),
    l1Approve: vi.fn(),
    l1Reject: vi.fn(),
  },
}));

import { financeInvoiceService } from '@/services/finance/financeInvoiceService';
import type { BillingInvoice } from '@/services/finance/financeInvoiceService';

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

const baseInvoice: BillingInvoice = {
  id: 1,
  invoiceNo: 'AR-1-1700000000-1',
  direction: 'RECEIVABLE',
  businessType: 'LAST_MILE',
  status: 'PENDING_L1',
  netAmount: 1234.5,
  currency: 'CNY',
  createdAt: '2026-10-17T00:00:00Z',
};

function asList(rows: BillingInvoice[]) {
  return { records: rows, total: rows.length };
}

describe('FinanceInvoiceList', () => {
  beforeEach(() => {
    vi.mocked(financeInvoiceService.list).mockReset();
    vi.mocked(financeInvoiceService.l1Approve).mockReset().mockResolvedValue(baseInvoice);
    vi.mocked(financeInvoiceService.submit).mockReset().mockResolvedValue(baseInvoice);
    vi.mocked(financeInvoiceService.l1Reject).mockReset().mockResolvedValue(baseInvoice);
  });

  it('renders the table with mocked invoices', async () => {
    vi.mocked(financeInvoiceService.list).mockResolvedValue(
      asList([
        baseInvoice,
        { ...baseInvoice, id: 2, invoiceNo: 'AP-1-1700000000-1', direction: 'PAYABLE' },
      ]),
    );

    const Wrapper = makeWrapper(['finance:invoice:view']);
    render(<Wrapper><FinanceInvoiceList /></Wrapper>);

    await waitFor(() => {
      expect(financeInvoiceService.list).toHaveBeenCalled();
    });
    expect(screen.getByText('AR-1-1700000000-1')).toBeInTheDocument();
    expect(screen.getByText('AP-1-1700000000-1')).toBeInTheDocument();
  });

  it('shows 通过 / 驳回 buttons only when status = PENDING_L1', async () => {
    vi.mocked(financeInvoiceService.list).mockResolvedValue(
      asList([
        baseInvoice,
        { ...baseInvoice, id: 2, invoiceNo: 'DRAFT-1', status: 'DRAFT' },
        { ...baseInvoice, id: 3, invoiceNo: 'L1-1',     status: 'L1_APPROVED' },
      ]),
    );

    const Wrapper = makeWrapper([
      'finance:invoice:view',
      'finance:invoice:submit',
      'finance:invoice:l1_approve',
    ]);
    render(<Wrapper><FinanceInvoiceList /></Wrapper>);

    // The PENDING_L1 row should expose 通过 / 驳回. DRAFT row should NOT
    // show them, and L1_APPROVED row should NOT show them either.
    // ProTable renders each row's option area inside an `.ant-pro-table
    // .ant-table-row` cell.
    await waitFor(() => {
      expect(screen.getByText('AR-1-1700000000-1')).toBeInTheDocument();
    });
    await waitFor(() => {
      expect(screen.getAllByRole('button', { name: /通.?过/ }).length).toBeGreaterThan(0);
    });

    const approveButtons = screen.getAllByRole('button', { name: /通.?过/ });
    expect(approveButtons.length).toBe(1); // only the PENDING_L1 row

    const rejectButtons = screen.getAllByRole('button', { name: /驳.?回/ });
    expect(rejectButtons.length).toBe(1); // only the PENDING_L1 row

    // DRAFT row should show 提交初审 (not 通过 / 驳回).
    const submitButtons = screen.getAllByRole('button', { name: /提.?交.?初.?审/ });
    expect(submitButtons.length).toBe(1);
  });

  it('clicking 通过 calls financeInvoiceService.l1Approve', async () => {
    vi.mocked(financeInvoiceService.list).mockResolvedValue(asList([baseInvoice]));

    const Wrapper = makeWrapper([
      'finance:invoice:view',
      'finance:invoice:l1_approve',
    ]);
    render(<Wrapper><FinanceInvoiceList /></Wrapper>);

    // Wait for the row to render before looking for its action button.
    await waitFor(() => {
      expect(screen.getByText('AR-1-1700000000-1')).toBeInTheDocument();
    });
    const approveBtn = await waitFor(() =>
      screen.getByRole('button', { name: /通.?过/ }),
    );
    // Use fireEvent rather than userEvent — ProTable's option column
    // wraps the action button in an overflow container whose
    // pointer-events behave differently under jsdom's simulated input
    // pipeline. fireEvent.click dispatches the React onClick handler
    // directly without simulating pointer events.
    fireEvent.click(approveBtn);

    await waitFor(() => {
      // handleApprove invokes financeInvoiceService.l1Approve(record.id)
      // with a single argument. Vitest's toHaveBeenCalledWith treats
      // [1] vs [1, undefined] as distinct calls (different arity), so
      // assert against the single-argument shape the component actually
      // emits.
      expect(financeInvoiceService.l1Approve).toHaveBeenCalledWith(1);
    });
  }, 15000);

  // Diagnostic helper — verifies the mock is wired up correctly so a
  // failure elsewhere in this file isn't blamed on the click handler.
  it('debug: directly calling l1Approve on the mock works', async () => {
    await financeInvoiceService.l1Approve(42);
    expect(financeInvoiceService.l1Approve).toHaveBeenCalledWith(42);
  });

  it('renders the status tag with the correct color per row', async () => {
    vi.mocked(financeInvoiceService.list).mockResolvedValue(
      asList([
        { ...baseInvoice, id: 1, invoiceNo: 'D-1',      status: 'DRAFT' },
        { ...baseInvoice, id: 2, invoiceNo: 'P-1',      status: 'PENDING_L1' },
        { ...baseInvoice, id: 3, invoiceNo: 'L1-1',     status: 'L1_APPROVED' },
        { ...baseInvoice, id: 4, invoiceNo: 'PU-1',     status: 'PUSHED' },
        { ...baseInvoice, id: 5, invoiceNo: 'RJ-1',     status: 'REJECTED' },
      ]),
    );

    const Wrapper = makeWrapper(['finance:invoice:view']);
    render(<Wrapper><FinanceInvoiceList /></Wrapper>);

    // Wait for the rows to render. We don't assert each status name
    // individually because ProTable may render statuses in a select
    // dropdown; instead we assert the tag colors via the rendered Tag
    // class names.
    await waitFor(() => {
      expect(screen.getByText('D-1')).toBeInTheDocument();
    });

    // antd <Tag color="gold"> renders class `ant-tag-gold`.
    const body = document.body;
    expect(body.innerHTML).toMatch(/ant-tag-gold/);    // PENDING_L1
    expect(body.innerHTML).toMatch(/ant-tag-green/);   // L1_APPROVED
    expect(body.innerHTML).toMatch(/ant-tag-blue/);    // PUSHED
    expect(body.innerHTML).toMatch(/ant-tag-red/);     // REJECTED

    // DRAFT — antd default Tag does NOT add a color modifier class;
    // verify the row is rendered at all.
    expect(within(body as HTMLElement).getByText('D-1')).toBeInTheDocument();
  });
});