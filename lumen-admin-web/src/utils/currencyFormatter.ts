/**
 * Lumen Admin — currency formatting helpers (Iteration 4.3.2 PR5).
 *
 * Tiny helpers shared across the Finance pages so amounts and rates render
 * consistently. Backend sends `BigDecimal` as JSON numbers; we round on
 * display only (the wire value stays untouched).
 */

/** Symbol table for the currencies currently in scope. */
const SYMBOL_BY_CURRENCY: Record<string, string> = {
  CNY: '¥',
  USD: '$',
  EUR: '€',
  JPY: '¥',
  HKD: 'HK$',
};

/**
 * Format a currency amount with two decimals + ISO-code.
 * Examples:
 *   formatCurrency(1234.5, 'CNY') → "¥1234.50"
 *   formatCurrency(0, 'USD')      → "$0.00"
 *   formatCurrency(1234.5)        → "¥1234.50"  (defaults to CNY)
 */
export const formatCurrency = (amount: number, currency: string = 'CNY'): string => {
  const symbol = SYMBOL_BY_CURRENCY[currency] ?? '';
  return `${symbol}${amount.toFixed(2)}`;
};

/**
 * Format an FX rate with 4 decimals.
 * Examples:
 *   formatRate(7.12345) → "7.1235"
 *   formatRate(1)       → "1.0000"
 */
export const formatRate = (rate: number): string => {
  return rate.toFixed(4);
};