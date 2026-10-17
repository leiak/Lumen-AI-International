/**
 * Lumen Admin — Finance Invoices page (Iteration 4.3.1 PR4).
 *
 * Page wrapper around `<FinanceInvoiceList />` so the router can
 * lazy-load a stable module path. The component itself lives under
 * `components/finance/` so unit tests don't have to traverse the page
 * tree.
 */
import FinanceInvoiceList from '@/components/finance/FinanceInvoiceList';

export default FinanceInvoiceList;