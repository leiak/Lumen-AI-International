/**
 * Lumen Admin — tax & FX helpers (Iteration 4.3.2 PR5).
 *
 * Pure functions for the gross→tax+net split and the FX conversion. Both
 * round to 4 decimal places (the same precision as the backend's
 * `BigDecimal` to avoid drift on display).
 */

/**
 * Split a gross amount (price inclusive of tax) into tax + net.
 *
 * Formula:
 *   tax  = gross − (gross / (1 + taxRate))
 *   net  = gross − tax
 *
 * Returns `{ tax: 0, net: gross }` when taxRate === 0 so zero-rated
 * invoices render predictably.
 */
export const splitGross = (
  gross: number,
  taxRate: number,
): { tax: number; net: number } => {
  if (taxRate === 0) return { tax: 0, net: gross };
  const tax = (gross * taxRate) / (1 + taxRate);
  return {
    tax: Math.round(tax * 10000) / 10000,
    net: Math.round((gross - tax) * 10000) / 10000,
  };
};

/**
 * Convert an amount in `fromCurrency` to `toCurrency` using an FX rate
 * (1 unit fromCurrency = fxRate units toCurrency).
 *
 * Rounds to 4 decimals to match backend BigDecimal precision.
 */
export const convertFx = (amount: number, fxRate: number): number => {
  return Math.round(amount * fxRate * 10000) / 10000;
};