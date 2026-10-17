/**
 * utils/taxCalculator.ts — smoke (Iteration 4.3.2 PR5).
 *
 * Locks the gross→tax+net split formula:
 *   splitGross(100, 0.13)  →  { tax: 13, net: 87 }
 *   tax  = gross * taxRate / (1 + taxRate)
 *   net  = gross - tax
 *
 * Note: 4 decimals of precision (matches backend BigDecimal). For tax
 * rate 0.13 on gross 100, tax = 100 * 0.13 / 1.13 = 11.504424...,
 * rounded to 4 decimals = 11.5044. We assert the math is sensible
 * (sum reconciles back to gross within the 4-decimal window).
 */

import { describe, it, expect } from 'vitest';

import { splitGross } from '@/utils/taxCalculator';

describe('taxCalculator', () => {
  it('splitGross(100, 0.13) returns { tax: ~11.5044, net: ~88.4956 } (sum reconciles to gross)', () => {
    const result = splitGross(100, 0.13);
    // Per the formula: tax ≈ 11.504424...; net ≈ 88.495575...
    expect(result.tax).toBe(11.5044);
    expect(result.net).toBe(88.4956);
    // Sum reconciles back to the original gross amount (modulo 4-decimal rounding).
    expect(result.tax + result.net).toBeCloseTo(100, 3);
  });
});
