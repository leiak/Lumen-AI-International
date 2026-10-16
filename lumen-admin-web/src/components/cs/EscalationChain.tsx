/**
 * EscalationChain — visualizes the L1 → L2 → L3 escalation ladder
 * (Iteration 4.2 PR6).
 *
 * Renders three Antd Tag chips (L1, L2, L3); the chip matching
 * `currentLevel` is highlighted in blue, others stay gray. If
 * `escalationCount > 0` an extra red "已升级 N 次" tag is appended.
 *
 * Used by the /cs/exceptions page so CS can see both the current
 * escalation level and the historical escalation count at once.
 */

import { Tag } from 'antd';

export type EscalationLevel = 'L1' | 'L2' | 'L3';

export interface EscalationChainProps {
  currentLevel: EscalationLevel;
  escalationCount: number;
}

const LEVELS: EscalationLevel[] = ['L1', 'L2', 'L3'];

export default function EscalationChain({ currentLevel, escalationCount }: EscalationChainProps) {
  return (
    <span data-testid="escalation-chain">
      {LEVELS.map((l) => (
        <Tag
          key={l}
          color={l === currentLevel ? 'blue' : 'default'}
          style={{ marginRight: 4 }}
          data-testid={`escalation-level-${l}`}
          data-current={l === currentLevel ? 'true' : 'false'}
        >
          {l}
        </Tag>
      ))}
      {escalationCount > 0 && (
        <Tag color="red" data-testid="escalation-count">
          已升级 {escalationCount} 次
        </Tag>
      )}
    </span>
  );
}