/**
 * Lumen Admin — root router & provider tree.
 *
 * Layer order (outer → inner):
 *   1. ConfigProvider (antd) with zhCN locale
 *   2. AntApp (antd App context — exposes message/modal/notification via useApp)
 *   3. ErrorBoundary level="app" — top-level crash fallback (Result 500 + reload)
 *      Sits OUTSIDE AuthProvider so synchronous throws from auth bootstrap
 *      (or its useEffect outside the try/catch) are still caught.
 *   4. LocaleProvider (i18n hook — exposes locale/t/setLocale; persisted to
 *      localStorage key `lumen_locale`). Sits OUTSIDE AuthProvider so the
 *      `/login` page can also use `useLocale()`.
 *   5. AuthProvider (our auth context — exposes currentUser/perms/login/etc.)
 *   6. BrowserRouter
 *   7. Routes:
 *        /login                      → standalone Login page (no auth required)
 *        everything else under       → ProtectedRoute → BasicLayout → nested routes
 *        /403, *                     → NotFound page
 *
 * NOTE: Theme tokens + useThemeMode wiring landed in Slice 4.1. Slice 4.3
 * added the LocaleProvider + dictionaries. Slice 4.4 wires the
 * LocaleSwitcher into BasicLayout's header.
 */

import { ConfigProvider, App as AntApp } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import { lazy, Suspense } from 'react';
import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom';

import BasicLayout from '@/layouts/BasicLayout';
import { AuthProvider } from '@/auth/AuthProvider';
import { ProtectedRoute } from '@/auth/ProtectedRoute';
import { ErrorBoundary } from '@/components/ErrorBoundary';
import { LoadingSkeleton } from '@/components/LoadingSkeleton';
import { LocaleProvider } from '@/locale';
import Login from '@/pages/Login';
import { darkTokens, lightTokens } from '@/theme/tokens';
import { useThemeMode } from '@/theme/useThemeMode';

const Users = lazy(() => import('@/pages/Users'));
const Roles = lazy(() => import('@/pages/Roles'));
const Permissions = lazy(() => import('@/pages/Permissions'));
const Dict = lazy(() => import('@/pages/Dict'));
const NumberRule = lazy(() => import('@/pages/NumberRule'));
const Notification = lazy(() => import('@/pages/Notification'));
const AuditLog = lazy(() => import('@/pages/AuditLog'));
// WMS masterdata pages — Iteration 3.0a PR1 vertical slice.
const WmsWarehouse = lazy(() => import('@/pages/Wms/Warehouse'));
const WmsLocation = lazy(() => import('@/pages/Wms/Location'));
const WmsItem = lazy(() => import('@/pages/Wms/Item'));
const WmsCustomer = lazy(() => import('@/pages/Wms/Customer'));
// WMS inventory ledger — PR2-T2.3.
const WmsInventory = lazy(() => import('@/pages/Wms/Inventory'));
// WMS threshold config form — PR3-T3.3.
const WmsThresholdForm = lazy(() => import('@/pages/Wms/Threshold/Form'));
// WMS inbound notes — PR3-T3.5 (DRAFT → RECEIVING → PUTAWAY → DONE 流程).
const WmsInboundList = lazy(() => import('@/pages/Wms/Inbound/List'));
const WmsInboundDetail = lazy(() => import('@/pages/Wms/Inbound/Detail'));
// WMS outbound notes — PR4-T4.3 (DRAFT → PICKING/REVIEW → SHIPPED → DONE 流程 + 库存锁).
const WmsOutboundList = lazy(() => import('@/pages/Wms/Outbound/List'));
const WmsOutboundDetail = lazy(() => import('@/pages/Wms/Outbound/Detail'));
// WMS inventory adjust notes — PR5-T5.2 (DRAFT → (PENDING_APPROVAL →) APPROVED → DONE / REJECTED / CANCELLED).
const WmsAdjustList = lazy(() => import('@/pages/Wms/Adjust/List'));
const WmsAdjustDetail = lazy(() => import('@/pages/Wms/Adjust/Detail'));
const NotFound = lazy(() => import('@/pages/NotFound'));

const pageFallback = <LoadingSkeleton type="page" />;

function App() {
  const [themeMode] = useThemeMode();
  return (
    <ConfigProvider
      locale={zhCN}
      theme={themeMode === 'dark' ? darkTokens : lightTokens}
    >
      <AntApp>
        <ErrorBoundary level="app">
          <LocaleProvider>
            <AuthProvider>
              <BrowserRouter>
                <Routes>
                  <Route path="/login" element={<Login />} />

                  <Route
                    element={
                      <ProtectedRoute>
                        <BasicLayout />
                      </ProtectedRoute>
                    }
                  >
                    <Route path="/" element={<Navigate to="/users" replace />} />
                    <Route
                      path="/users"
                      element={<Suspense fallback={pageFallback}><Users /></Suspense>}
                    />
                    <Route
                      path="/roles"
                      element={<Suspense fallback={pageFallback}><Roles /></Suspense>}
                    />
                    <Route
                      path="/permissions"
                      element={<Suspense fallback={pageFallback}><Permissions /></Suspense>}
                    />
                    <Route
                      path="/dicts"
                      element={<Suspense fallback={pageFallback}><Dict /></Suspense>}
                    />
                    <Route
                      path="/number-rules"
                      element={<Suspense fallback={pageFallback}><NumberRule /></Suspense>}
                    />
                    <Route
                      path="/notifications"
                      element={<Suspense fallback={pageFallback}><Notification /></Suspense>}
                    />
                    <Route
                      path="/audit-logs"
                      element={<Suspense fallback={pageFallback}><AuditLog /></Suspense>}
                    />
                    <Route
                      path="/wms/warehouses"
                      element={<Suspense fallback={pageFallback}><WmsWarehouse /></Suspense>}
                    />
                    <Route
                      path="/wms/locations"
                      element={<Suspense fallback={pageFallback}><WmsLocation /></Suspense>}
                    />
                    <Route
                      path="/wms/items"
                      element={<Suspense fallback={pageFallback}><WmsItem /></Suspense>}
                    />
                    <Route
                      path="/wms/customers"
                      element={<Suspense fallback={pageFallback}><WmsCustomer /></Suspense>}
                    />
                    <Route
                      path="/wms/inventories"
                      element={<Suspense fallback={pageFallback}><WmsInventory /></Suspense>}
                    />
                    <Route
                      path="/wms/settings/threshold"
                      element={<Suspense fallback={pageFallback}><WmsThresholdForm /></Suspense>}
                    />
                    <Route
                      path="/wms/inbound-notes"
                      element={<Suspense fallback={pageFallback}><WmsInboundList /></Suspense>}
                    />
                    <Route
                      path="/wms/inbound-notes/new"
                      element={<Suspense fallback={pageFallback}><WmsInboundDetail /></Suspense>}
                    />
                    <Route
                      path="/wms/inbound-notes/:id"
                      element={<Suspense fallback={pageFallback}><WmsInboundDetail /></Suspense>}
                    />
                    <Route
                      path="/wms/outbound-notes"
                      element={<Suspense fallback={pageFallback}><WmsOutboundList /></Suspense>}
                    />
                    <Route
                      path="/wms/outbound-notes/new"
                      element={<Suspense fallback={pageFallback}><WmsOutboundDetail /></Suspense>}
                    />
                    <Route
                      path="/wms/outbound-notes/:id"
                      element={<Suspense fallback={pageFallback}><WmsOutboundDetail /></Suspense>}
                    />
                    <Route
                      path="/wms/inventory-adjusts"
                      element={<Suspense fallback={pageFallback}><WmsAdjustList /></Suspense>}
                    />
                    <Route
                      path="/wms/inventory-adjusts/new"
                      element={<Suspense fallback={pageFallback}><WmsAdjustDetail /></Suspense>}
                    />
                    <Route
                      path="/wms/inventory-adjusts/:id"
                      element={<Suspense fallback={pageFallback}><WmsAdjustDetail /></Suspense>}
                    />
                  </Route>

                  <Route path="/403" element={<Suspense fallback={pageFallback}><NotFound /></Suspense>} />
                  <Route path="*" element={<Suspense fallback={pageFallback}><NotFound /></Suspense>} />
                </Routes>
              </BrowserRouter>
            </AuthProvider>
          </LocaleProvider>
        </ErrorBoundary>
      </AntApp>
    </ConfigProvider>
  );
}

export default App;