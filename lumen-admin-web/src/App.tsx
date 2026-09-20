/**
 * Lumen Admin — root router & provider tree.
 *
 * Layer order (outer → inner):
 *   1. ConfigProvider (antd) with zhCN locale
 *   2. AntApp (antd App context — exposes message/modal/notification via useApp)
 *   3. ErrorBoundary level="app" — top-level crash fallback (Result 500 + reload)
 *      Sits OUTSIDE AuthProvider so synchronous throws from auth bootstrap
 *      (or its useEffect outside the try/catch) are still caught.
 *   4. AuthProvider (our auth context — exposes currentUser/perms/login/etc.)
 *   5. BrowserRouter
 *   6. Routes:
 *        /login                      → standalone Login page (no auth required)
 *        everything else under       → ProtectedRoute → BasicLayout → nested routes
 *        /403, *                     → NotFound page
 *
 * NOTE: LocaleProvider + theme/useThemeMode wiring lands in Slice 4 — for now
 * we keep Antd's default zhCN locale and let future tasks lift it into a
 * context-driven custom theme.
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
import Login from '@/pages/Login';

const Users = lazy(() => import('@/pages/Users'));
const Roles = lazy(() => import('@/pages/Roles'));
const Permissions = lazy(() => import('@/pages/Permissions'));
const Dict = lazy(() => import('@/pages/Dict'));
const NumberRule = lazy(() => import('@/pages/NumberRule'));
const Notification = lazy(() => import('@/pages/Notification'));
const AuditLog = lazy(() => import('@/pages/AuditLog'));
const NotFound = lazy(() => import('@/pages/NotFound'));

const pageFallback = <LoadingSkeleton type="page" />;

function App() {
  return (
    <ConfigProvider locale={zhCN}>
      <AntApp>
        <ErrorBoundary level="app">
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
                </Route>

                <Route path="/403" element={<Suspense fallback={pageFallback}><NotFound /></Suspense>} />
                <Route path="*" element={<Suspense fallback={pageFallback}><NotFound /></Suspense>} />
              </Routes>
            </BrowserRouter>
          </AuthProvider>
        </ErrorBoundary>
      </AntApp>
    </ConfigProvider>
  );
}

export default App;