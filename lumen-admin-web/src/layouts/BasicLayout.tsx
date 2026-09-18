/**
 * Lumen Admin — BasicLayout (ProLayout shell + nested route outlet).
 *
 * Routes defined here MUST match the nested <Route> entries in App.tsx.
 * ProLayout's modern API takes `route` (the menu tree) plus `menu` (layout
 * options like type/collapsed). The plan's `onMenuClick` prop doesn't exist
 * on the current ProLayout type — we rely on ProLayout's built-in navigation
 * via react-router (location prop) and only intercept the header logo click.
 */

import { ProLayout } from '@ant-design/pro-components';
import type { MenuDataItem } from '@ant-design/pro-components';
import { Outlet, useLocation, useNavigate } from 'react-router-dom';

const menuRoute: { path: string; routes: MenuDataItem[] } = {
  path: '/',
  routes: [
    { path: '/users', name: '用户管理' },
    { path: '/roles', name: '角色管理' },
    { path: '/permissions', name: '权限矩阵' },
    { path: '/dicts', name: '数据字典' },
    { path: '/number-rules', name: '编号规则' },
    { path: '/notifications', name: '通知模板' },
    { path: '/audit-logs', name: '审计日志' },
  ],
};

export default function BasicLayout() {
  const nav = useNavigate();
  const location = useLocation();

  return (
    <ProLayout
      title="Lumen Admin"
      layout="mix"
      location={location}
      route={menuRoute}
      menu={{ type: 'group' }}
      onMenuHeaderClick={() => nav('/')}
    >
      <Outlet />
    </ProLayout>
  );
}
