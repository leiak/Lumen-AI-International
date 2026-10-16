/**
 * SlaCountdownTag — color thresholds + label text (Iteration 4.2 PR6).
 *
 * Three cases lock the visual contract:
 *   1. ratio < 0.8  → green  "剩余 Nmin / Tmin"
 *   2. 0.8 ≤ ratio < 1 → orange "剩余 Nmin / Tmin"
 *   3. ratio ≥ 1    → red    "超期 Nmin / Tmin"
 */

import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';

import SlaCountdownTag from '@/components/cs/SlaCountdownTag';

describe('SlaCountdownTag', () => {
  it('renders green "remaining" tag when elapsed is well under target', () => {
    render(<SlaCountdownTag elapsedMinutes={50} targetMinutes={240} />);
    const tag = screen.getByTestId('sla-countdown-tag');
    expect(tag.className).toMatch(/ant-tag-green/);
    expect(tag).toHaveTextContent('剩余 190min / 240min');
  });

  it('renders orange "remaining" tag when elapsed ≥ 80% of target but still under', () => {
    render(<SlaCountdownTag elapsedMinutes={200} targetMinutes={240} />);
    const tag = screen.getByTestId('sla-countdown-tag');
    expect(tag.className).toMatch(/ant-tag-orange/);
    expect(tag).toHaveTextContent('剩余 40min / 240min');
  });

  it('renders red "超期" tag when elapsed ≥ target', () => {
    render(<SlaCountdownTag elapsedMinutes={260} targetMinutes={240} />);
    const tag = screen.getByTestId('sla-countdown-tag');
    expect(tag.className).toMatch(/ant-tag-red/);
    expect(tag).toHaveTextContent('超期 20min / 240min');
  });
});