/**
 * Lumen Admin — root router.
 *
 * Top-level routes:
 *   - /login       → standalone Login page (no layout)
 *   - everything else → BasicLayout shell with nested route outlet
 *
 * Nested routes under BasicLayout MUST match the menu entries defined in
 * src/layouts/BasicLayout.tsx so the active menu item highlights correctly.
 */

import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom';
import { ConfigProvider, App as AntApp } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import BasicLayout from '@/layouts/BasicLayout';
import Login from '@/pages/Login';
import Users from '@/pages/Users';
import Roles from '@/pages/Roles';
import Permissions from '@/pages/Permissions';
import Dict from '@/pages/Dict';
import NumberRule from '@/pages/NumberRule';
import Notification from '@/pages/Notification';
import AuditLog from '@/pages/AuditLog';

function App() {
  return (
    <ConfigProvider locale={zhCN}>
      <AntApp>
        <BrowserRouter>
          <Routes>
            <Route path="/login" element={<Login />} />
            <Route element={<BasicLayout />}>
              <Route path="/" element={<Users />} />
              <Route path="/users" element={<Users />} />
              <Route path="/roles" element={<Roles />} />
              <Route path="/permissions" element={<Permissions />} />
              <Route path="/dicts" element={<Dict />} />
              <Route path="/number-rules" element={<NumberRule />} />
              <Route path="/notifications" element={<Notification />} />
              <Route path="/audit-logs" element={<AuditLog />} />
              <Route path="*" element={<Navigate to="/" replace />} />
            </Route>
          </Routes>
        </BrowserRouter>
      </AntApp>
    </ConfigProvider>
  );
}

export default App;
