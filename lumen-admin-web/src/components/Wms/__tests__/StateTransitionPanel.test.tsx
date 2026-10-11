/**
 * StateTransitionPanel — smoke + perm/status gating tests (PR5-T5.3).
 *
 * Covers the two gating rules:
 *   1. Only the transition whose `fromStatus` matches the current note
 *      status is visible.
 *   2. A transition is hidden if the user lacks `permCode`.
 *
 * Mounting: the panel uses `useAccess` which reads from `AuthContext`,
 * so we wrap with an `<AuthContext.Provider>` carrying the perms we want.
 *
 * AntD's <Button> auto-inserts a non-breaking space between CJK glyphs;
 * tests match by role with regex name (instead of literal 提交) so the
 * matcher is version-resistant.
 */

import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { StateTransitionPanel, type TransitionConfig } from '@/components/Wms/StateTransitionPanel';
import { AuthContext } from '@/auth/AuthProvider';
import type { AuthContextValue } from '@/auth/types';

function makeWrapper(perms: string[]): React.FC<{ children: React.ReactNode }> {
  const user = perms.length > 0
    ? { userId: 1, tenantId: 1, username: 'tester', roles: ['OPS'], perms }
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
    <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
  );
}

const transitions: TransitionConfig[] = [
  { key: 'submit',  label: '提交', fromStatus: ['DRAFT'], permCode: 'wms:inbound:submit' },
  { key: 'approve', label: '审批', fromStatus: ['PENDING_APPROVAL'], permCode: 'wms:inbound:approve',
    confirmTitle: '确认审批?' },
  { key: 'cancel',  label: '取消', fromStatus: ['DRAFT','PENDING_APPROVAL'],
    permCode: 'wms:inbound:cancel', danger: true, confirmTitle: '确认取消?' },
];

describe('StateTransitionPanel', () => {
  it('only renders the transition whose fromStatus matches the current status', () => {
    const Wrapper = makeWrapper(['wms:inbound:submit', 'wms:inbound:cancel']);
    render(
      <Wrapper>
        <StateTransitionPanel
          status="DRAFT"
          transitions={transitions}
          onAction={() => {}}
        />
      </Wrapper>,
    );

    expect(screen.getByRole('button', { name: /提.?交/ })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /取.?消/ })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /审.?批/ })).not.toBeInTheDocument();
  });

  it('hides a transition whose permCode the user does not have', () => {
    // User can submit but cannot cancel.
    const Wrapper = makeWrapper(['wms:inbound:submit']);
    render(
      <Wrapper>
        <StateTransitionPanel
          status="DRAFT"
          transitions={transitions}
          onAction={() => {}}
        />
      </Wrapper>,
    );

    expect(screen.getByRole('button', { name: /提.?交/ })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /取.?消/ })).not.toBeInTheDocument();
  });

  it('invokes onAction with the transition key when clicked (no confirm)', async () => {
    const onAction = vi.fn();
    // Use a no-confirm transition so we don't have to interact with Popconfirm.
    const flat: TransitionConfig[] = [
      { key: 'approve', label: '审批通过', fromStatus: ['PENDING_APPROVAL'], permCode: 'wms:inbound:approve' },
    ];
    const Wrapper = makeWrapper(['wms:inbound:approve']);
    render(
      <Wrapper>
        <StateTransitionPanel
          status="PENDING_APPROVAL"
          transitions={flat}
          onAction={onAction}
        />
      </Wrapper>,
    );

    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: /审.?批.?通.?过/ }));
    expect(onAction).toHaveBeenCalledWith('approve');
  });

  it('renders nothing when no transition matches', () => {
    const Wrapper = makeWrapper(['wms:inbound:submit']);
    const { container } = render(
      <Wrapper>
        <StateTransitionPanel
          status="DONE"
          transitions={transitions}
          onAction={() => {}}
        />
      </Wrapper>,
    );
    // No buttons should be rendered.
    expect(container.querySelectorAll('button').length).toBe(0);
  });
});
