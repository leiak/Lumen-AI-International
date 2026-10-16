/**
 * EscalationChain — level highlighting + escalation count badge
 * (Iteration 4.2 PR6).
 *
 * Locks:
 *   1. With escalationCount = 0 no extra badge is rendered.
 *   2. With escalationCount > 0 a red "已升级 N 次" badge appears.
 *   3. The currentLevel chip is highlighted (data-current=true) and
 *      the other two remain un-highlighted.
 */

import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';

import EscalationChain from '@/components/cs/EscalationChain';

describe('EscalationChain', () => {
  it('renders three level tags and no escalation-count badge when count = 0', () => {
    render(<EscalationChain currentLevel="L2" escalationCount={0} />);

    expect(screen.getByTestId('escalation-chain')).toBeInTheDocument();
    expect(screen.getByTestId('escalation-level-L1')).toHaveAttribute('data-current', 'false');
    expect(screen.getByTestId('escalation-level-L2')).toHaveAttribute('data-current', 'true');
    expect(screen.getByTestId('escalation-level-L3')).toHaveAttribute('data-current', 'false');
    expect(screen.queryByTestId('escalation-count')).not.toBeInTheDocument();
  });

  it('appends a red escalation-count tag when count = 2', () => {
    render(<EscalationChain currentLevel="L3" escalationCount={2} />);

    const badge = screen.getByTestId('escalation-count');
    expect(badge).toBeInTheDocument();
    expect(badge.className).toMatch(/ant-tag-red/);
    expect(badge).toHaveTextContent('已升级 2 次');

    // Highlight sits on L3.
    expect(screen.getByTestId('escalation-level-L3')).toHaveAttribute('data-current', 'true');
    expect(screen.getByTestId('escalation-level-L1')).toHaveAttribute('data-current', 'false');
    expect(screen.getByTestId('escalation-level-L2')).toHaveAttribute('data-current', 'false');
  });
});