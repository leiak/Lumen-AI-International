/**
 * L2ApprovalButtons — gating + render tests (Iteration 4.3.2 PR5).
 *
 * Locks:
 *   1. Renders nothing (returns null) when currentStatus !== 'PENDING_L2'.
 *   2. Renders the L2 通过 / L2 驳回 buttons when status = PENDING_L2
 *      and currentUserId !== l1ApproverId (the happy path).
 *
 * Mirrors the same wiring as `FinanceInvoiceList.test.tsx`:
 *   - mock the @/services/request module (defensive — though this
 *     component does not import request directly).
 *   - wrap with <AuthContext.Provider> + <ConfigProvider>.
 */

import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { ConfigProvider } from 'antd';
import zhCN from 'antd/locale/zh_CN';

import L2ApprovalButtons from '@/components/finance/L2ApprovalButtons';
import { AuthContext } from '@/auth/AuthProvider';
import type { AuthContextValue } from '@/auth/types';

// jsdom doesn't ship matchMedia; antd's App component may probe it.
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

function makeWrapper(userId: number, perms: string[]): React.FC<{ children: React.ReactNode }> {
  const value: AuthContextValue = {
    user: { userId, tenantId: 1, username: `u${userId}`, roles: ['FINANCE_OPS'], perms },
    isAuthenticated: true,
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

describe('L2ApprovalButtons', () => {
  it('renders nothing when currentStatus is NOT PENDING_L2', () => {
    const Wrapper = makeWrapper(99, ['finance:invoice:l2_approve']);
    const { container } = render(
      <Wrapper>
        <L2ApprovalButtons
          currentStatus="PENDING_L1"
          l1ApproverId={42}
          currentUserId={99}
          onL2Approve={() => {}}
          onL2Reject={() => {}}
        />
      </Wrapper>,
    );
    // Guard 1: status mismatch → component returns null → empty container.
    expect(container.firstChild).toBeNull();
    expect(screen.queryByText(/L2.?通过/)).not.toBeInTheDocument();
    expect(screen.queryByText(/L2.?驳回/)).not.toBeInTheDocument();
  });

  it('renders the L2 通过 / L2 驳回 buttons when status = PENDING_L2 and currentUserId differs from l1ApproverId', () => {
    const onL2Approve = vi.fn();
    const onL2Reject = vi.fn();
    const Wrapper = makeWrapper(99, ['finance:invoice:l2_approve']);

    render(
      <Wrapper>
        <L2ApprovalButtons
          currentStatus="PENDING_L2"
          l1ApproverId={42}
          currentUserId={99}
          onL2Approve={onL2Approve}
          onL2Reject={onL2Reject}
        />
      </Wrapper>,
    );

    // Happy path: status = PENDING_L2 and current user (99) ≠ L1 approver (42).
    expect(screen.getByRole('button', { name: /L2.?通过/ })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /L2.?驳回/ })).toBeInTheDocument();
  });
});
