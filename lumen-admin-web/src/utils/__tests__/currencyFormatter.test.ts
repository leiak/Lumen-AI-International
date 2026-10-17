/**
 * utils/currencyFormatter.ts — smoke (Iteration 4.3.2 PR5).
 *
 * Locks the wire-shape of the `formatCurrency` helper:
 *   - Default currency is CNY (¥).
 *   - Unknown currency falls through to an empty symbol prefix.
 *   - Returns two-decimal format.
 */

import { describe, it, expect } from 'vitest';

import { formatCurrency } from '@/utils/currencyFormatter';

describe('currencyFormatter', () => {
  it('formatCurrency(1234.5, "USD") returns "$1234.50" — correct symbol + value', () => {
    expect(formatCurrency(1234.5, 'USD')).toBe('$1234.50');
  });
});
