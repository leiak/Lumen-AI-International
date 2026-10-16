/**
 * SlaCountdownTag — color-coded SLA countdown badge (Iteration 4.2 PR6).
 *
 * Visual states (driven by `elapsedMinutes / targetMinutes`):
 *   < 0.80  → green   (well within budget)
 *   < 1.00  → orange  (approaching breach)
 *   ≥ 1.00  → red     (breached / overdue)
 *
 * Label shows remaining minutes when still under target, and overdue
 * minutes (prefixed "超期") once breached. Used by the /cs/exceptions
 * page so CS can spot at-risk work orders in one glance.
 */

import { Tag } from 'antd';

export interface SlaCountdownTagProps {
  elapsedMinutes: number;
  targetMinutes: number;
}

export default function SlaCountdownTag({ elapsedMinutes, targetMinutes }: SlaCountdownTagProps) {
  // Avoid NaN / Infinity when targetMinutes is 0 or negative.
  const safeTarget = targetMinutes > 0 ? targetMinutes : 1;
  const ratio = elapsedMinutes / safeTarget;

  const color = ratio >= 1 ? 'red' : ratio >= 0.8 ? 'orange' : 'green';

  const remaining = targetMinutes - elapsedMinutes;
  const label = remaining >= 0
    ? `剩余 ${remaining}min / ${targetMinutes}min`
    : `超期 ${-remaining}min / ${targetMinutes}min`;

  return <Tag color={color} data-testid="sla-countdown-tag">{label}</Tag>;
}