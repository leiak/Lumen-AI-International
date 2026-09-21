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
 * Menu labels are pulled from i18n via `useLocale().t('menu.*')` so that
 * switching locale re-renders the sidebar with translated names.
 */

import { ProLayout } from '@ant-design/pro-components';
import type { MenuDataItem } from '@ant-design/pro-components';
import { useMemo } from 'react';
import { Outlet, useLocation, useNavigate } from 'react-router-dom';

import { useAccess } from '@/hooks/useAccess';
import { LocaleSwitcher } from '@/components/LocaleSwitcher';
import { ThemeToggle } from '@/components/ThemeToggle';
import { useLocale } from '@/locale';

type MenuEntry = MenuDataItem & { perm?: string };

export default function BasicLayout() {
  const nav = useNavigate();
  const location = useLocation();
  const access = useAccess();
  const { t } = useLocale();

  const menuRoute = useMemo<{ path: string; routes: MenuEntry[] }>(
    () => ({
      path: '/',
      routes: [
        { path: '/users', name: t('menu.users'), perm: 'user:list' },
        { path: '/roles', name: t('menu.roles'), perm: 'role:list' },
        { path: '/permissions', name: t('menu.permissions'), perm: 'permission:list' },
        { path: '/dicts', name: t('menu.dicts'), perm: 'dict:list' },
        { path: '/number-rules', name: t('menu.numberRules'), perm: 'number-rule:list' },
        { path: '/notifications', name: t('menu.notifications'), perm: 'notification_template:list' },
        { path: '/audit-logs', name: t('menu.auditLogs'), perm: 'audit:list' },
      ],
    }),
    [t],
  );

  const visibleRoutes = useMemo(
    () => menuRoute.routes.filter((r) => !r.perm || access.perms.includes(r.perm)),
    [access.perms, menuRoute],
  );

  return (
    <ProLayout
      title="Lumen Admin"
      layout="mix"
      location={location}
      route={{ path: menuRoute.path, routes: visibleRoutes }}
      menu={{ type: 'group' }}
      onMenuHeaderClick={() => nav('/')}
      actionsRender={() => [
        <LocaleSwitcher key="locale-switcher" />,
        <ThemeToggle key="theme-toggle" />,
      ]}
    >
      <Outlet />
    </ProLayout>
  );
}
