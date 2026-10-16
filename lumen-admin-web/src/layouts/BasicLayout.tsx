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
 *
 * Top-level groups (rendered as ProLayout sections by `menu.type = 'group'`):
 *   RBAC    — system administration
 *   WMS     — warehouse management (Iteration 3.0a)
 *   Dispatch — delivery dispatch masterdata (Iteration 4.1 PR3)
 *
 * Note: backend endpoints for dispatch masterdata remain at /api/v1/wms/*
 * (V19 schema lives alongside wms_* tables); only the FE route prefix is
 * /dispatch/* per spec §9.
 */

import { ProLayout } from '@ant-design/pro-components';
import type { MenuDataItem } from '@ant-design/pro-components';
import { CarOutlined, CustomerServiceOutlined } from '@ant-design/icons';
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
        // ---------------------------------------------------------------
        // RBAC / platform administration
        // ---------------------------------------------------------------
        { path: '/users', name: t('menu.users'), perm: 'user:list' },
        { path: '/roles', name: t('menu.roles'), perm: 'role:list' },
        { path: '/permissions', name: t('menu.permissions'), perm: 'permission:list' },
        { path: '/dicts', name: t('menu.dicts'), perm: 'dict:list' },
        { path: '/number-rules', name: t('menu.numberRules'), perm: 'number-rule:list' },
        { path: '/notifications', name: t('menu.notifications'), perm: 'notification_template:list' },
        { path: '/audit-logs', name: t('menu.auditLogs'), perm: 'audit:list' },
        // ---------------------------------------------------------------
        // WMS (Iteration 3.0a vertical slice)
        // ---------------------------------------------------------------
        // WMS masterdata (PR1).
        { path: '/wms/warehouses', name: t('menu.wms.warehouses'), perm: 'wms:warehouse:list' },
        { path: '/wms/locations', name: t('menu.wms.locations'), perm: 'wms:location:list' },
        { path: '/wms/items', name: t('menu.wms.items'), perm: 'wms:item:list' },
        { path: '/wms/customers', name: t('menu.wms.customers'), perm: 'wms:customer:list' },
        // WMS inventory ledger (PR2-T2.3).
        { path: '/wms/inventories', name: t('menu.wms.inventories'), perm: 'wms:inventory:list' },
        // WMS threshold config (PR3-T3.3).
        { path: '/wms/settings/threshold', name: t('menu.wms.threshold'), perm: 'wms:threshold:get' },
        // WMS inbound notes (PR3-T3.5).
        { path: '/wms/inbound-notes', name: t('menu.wms.inbound'), perm: 'wms:inbound:list' },
        // WMS outbound notes (PR4-T4.3).
        { path: '/wms/outbound-notes', name: t('menu.wms.outbound'), perm: 'wms:outbound:list' },
        // WMS inventory adjust notes (PR5-T5.2).
        { path: '/wms/inventory-adjusts', name: t('menu.wms.adjust'), perm: 'wms:adjust:list' },
        // ---------------------------------------------------------------
        // Dispatch masterdata (Iteration 4.1 PR3)
        //   Spec §9 mandates /dispatch/* route prefix (separate from WMS).
        //   Backend APIs stay at /api/v1/wms/{vehicles,drivers,carriers}
        //   (existing wms_* controller convention) — only FE routes move.
        // ---------------------------------------------------------------
        {
          path: '/dispatch',
          name: t('menu.dispatch'),
          icon: <CarOutlined />,
          // ProLayout's MenuDataItem types `routes` as `undefined`; nested
          // children MUST be expressed via the `children` field.
          children: [
            { path: '/dispatch/vehicles',  name: t('menu.dispatch.vehicles'),  perm: 'dispatch:vehicle:list' },
            { path: '/dispatch/drivers',   name: t('menu.dispatch.drivers'),   perm: 'dispatch:driver:list' },
            { path: '/dispatch/carriers',  name: t('menu.dispatch.carriers'),  perm: 'dispatch:carrier:list' },
            { path: '/dispatch/orders',    name: t('menu.dispatch.orders'),    perm: 'dispatch:order:list' },
            { path: '/dispatch/exceptions', name: t('menu.dispatch.exceptions'), perm: 'dispatch:exception:list' },
            // Iteration 4.1 PR5 — vehicle utilization dashboard
            { path: '/dispatch/dashboard', name: t('menu.dispatch.dashboard'),  perm: 'dispatch:dashboard:view' },
          ],
        },
        // ---------------------------------------------------------------
        // Customer service (Iteration 4.2 PR2) — SLA + business calendar + holidays.
        //   Backend APIs live at /api/v1/cs/* (new namespace). Perms land
        //   in PR5 V25 migration; until then menu items are visible but
        //   @PreAuthorize calls will return 403 for users without the codes.
        // ---------------------------------------------------------------
        {
          path: '/cs',
          name: t('menu.cs'),
          icon: <CustomerServiceOutlined />,
          children: [
            { path: '/cs/sla-configs', name: t('menu.cs.slaConfigs'), perm: 'cs:sla:config' },
            { path: '/cs/calendar',    name: t('menu.cs.calendar'),    perm: 'cs:calendar:config' },
            { path: '/cs/holidays',    name: t('menu.cs.holidays'),    perm: 'cs:holiday:config' },
            // Iteration 4.2 PR6 — SLA-perspective exception view
            { path: '/cs/exceptions',  name: t('menu.cs.exceptions'),  perm: 'cs:exception:view' },
          ],
        },
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
