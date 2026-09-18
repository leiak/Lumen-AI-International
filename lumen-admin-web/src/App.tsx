/**
 * Lumen Admin — root router.
 *
 * Top-level routes:
 *   - /login       → standalone Login page (no layout)
 *   - everything else → BasicLayout shell with nested route outlet
 *
 * Nested routes under BasicLayout MUST match the menu entries defined in
 * src/layouts/BasicLayout.tsx so the active menu item highlights correctly.
 *
 * Page modules are loaded via React.lazy so the heavy pro-components /
 * antd surface area is only paid for when the user navigates to a given
 * route. The Suspense fallback keeps the UI responsive during the chunk
 * fetch.
 */

import { ConfigProvider, App as AntApp, Spin } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import { lazy, Suspense } from 'react';
import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom';
import BasicLayout from '@/layouts/BasicLayout';
import Login from '@/pages/Login';

const Users = lazy(() => import('@/pages/Users'));
const Roles = lazy(() => import('@/pages/Roles'));
const Permissions = lazy(() => import('@/pages/Permissions'));
const Dict = lazy(() => import('@/pages/Dict'));
const NumberRule = lazy(() => import('@/pages/NumberRule'));
const Notification = lazy(() => import('@/pages/Notification'));
const AuditLog = lazy(() => import('@/pages/AuditLog'));

function App() {
  return (
    <ConfigProvider locale={zhCN}>
      <AntApp>
        <BrowserRouter>
          <Routes>
            <Route path="/login" element={<Login />} />
            <Route
              element={
                <Suspense fallback={<Spin size="large" style={{ display: 'block', margin: '120px auto' }} />}>
                  <BasicLayout />
                </Suspense>
              }
            >
              <Route
                path="/"
                element={
                  <Suspense fallback={<Spin />}>
                    <Users />
                  </Suspense>
                }
              />
              <Route
                path="/users"
                element={
                  <Suspense fallback={<Spin />}>
                    <Users />
                  </Suspense>
                }
              />
              <Route
                path="/roles"
                element={
                  <Suspense fallback={<Spin />}>
                    <Roles />
                  </Suspense>
                }
              />
              <Route
                path="/permissions"
                element={
                  <Suspense fallback={<Spin />}>
                    <Permissions />
                  </Suspense>
                }
              />
              <Route
                path="/dicts"
                element={
                  <Suspense fallback={<Spin />}>
                    <Dict />
                  </Suspense>
                }
              />
              <Route
                path="/number-rules"
                element={
                  <Suspense fallback={<Spin />}>
                    <NumberRule />
                  </Suspense>
                }
              />
              <Route
                path="/notifications"
                element={
                  <Suspense fallback={<Spin />}>
                    <Notification />
                  </Suspense>
                }
              />
              <Route
                path="/audit-logs"
                element={
                  <Suspense fallback={<Spin />}>
                    <AuditLog />
                  </Suspense>
                }
              />
              <Route path="*" element={<Navigate to="/" replace />} />
            </Route>
          </Routes>
        </BrowserRouter>
      </AntApp>
    </ConfigProvider>
  );
}

export default App;
