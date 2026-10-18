/**
 * Lumen Admin — Finance reconciliation page (Iteration 4.4 PR3).
 *
 * Page wrapper around `<ReconciliationList />` so the router can
 * lazy-load a stable module path. Mirrors the 4.3.x Invoices /
 * Adjustments wrappers.
 */
import ReconciliationList from '@/components/finance/ReconciliationList';

export default ReconciliationList;