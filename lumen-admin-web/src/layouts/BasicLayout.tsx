/**
 * Lumen Admin — BasicLayout (ProLayout shell + nested route outlet).
 *
 * Routes defined here MUST match the nested <Route> entries in App.tsx.
 *
 * Perm filtering: each menu item MAY declare a `perm` (e.g. "user:list").
 * If set, the item is hidden when the current user lacks that permission.
 * Items without `perm` are always visible (e.g. dashboard, settings).
 *
 * The `perm` strings here mirror the `@PreAuthorize("hasAuthority(...)")`
 * codes on the backend. Source of truth lives in the per-module controllers
 * (e.g. `lumen-rbac/.../UserController.java`) and the V2/V6/V14 seed rows
 * in `lumen-bootstrap/.../db/migration/`. Keep them in sync.
 *
 * Slice 4 will move the hardcoded `name` strings into i18n keys via
 * `useLocale().t('menu.users')`. Keep them as Chinese literals for now.
 */

import { ProLayout } from '@ant-design/pro-components';
import type { MenuDataItem } from '@ant-design/pro-components';
import { useMemo } from 'react';
import { Outlet, useLocation, useNavigate } from 'react-router-dom';

import { useAccess } from '@/hooks/useAccess';

type MenuEntry = MenuDataItem & { perm?: string };

const menuRoute: { path: string; routes: MenuEntry[] } = {
  path: '/',
  routes: [
    { path: '/users', name: '用户管理', perm: 'user:list' },
    { path: '/roles', name: '角色管理', perm: 'role:list' },
    { path: '/permissions', name: '权限矩阵', perm: 'permission:list' },
    { path: '/dicts', name: '数据字典', perm: 'dict:list' },
    { path: '/number-rules', name: '编号规则', perm: 'number-rule:list' },
    { path: '/notifications', name: '通知模板', perm: 'notification_template:list' },
    { path: '/audit-logs', name: '审计日志', perm: 'audit:list' },
  ],
};

export default function BasicLayout() {
  const nav = useNavigate();
  const location = useLocation();
  const access = useAccess();

  const visibleRoutes = useMemo(
    () => menuRoute.routes.filter((r) => !r.perm || access.canRead(r.perm)),
    [access.perms],
  );

  return (
    <ProLayout
      title="Lumen Admin"
      layout="mix"
      location={location}
      route={{ path: menuRoute.path, routes: visibleRoutes }}
      menu={{ type: 'group' }}
      onMenuHeaderClick={() => nav('/')}
    >
      <Outlet />
    </ProLayout>
  );
}
