/**
 * Lumen Admin — English translations.
 *
 * MUST keep the same key set as zh-CN.ts; type-check enforces this.
 */
import type { ZhKey } from './zh-CN';

export const enUS: Record<ZhKey, string> = {
  'menu.users': 'Users',
  'menu.roles': 'Roles',
  'menu.permissions': 'Permissions',
  'menu.dicts': 'Dictionaries',
  'menu.numberRules': 'Number Rules',
  'menu.notifications': 'Notification Templates',
  'menu.auditLogs': 'Audit Logs',
  'menu.wms': 'Warehouse Management',
  'menu.wms.warehouses': 'Warehouses',
  'menu.wms.locations': 'Locations',
  'menu.wms.items': 'Items',
  'menu.wms.customers': 'Customers',
  'menu.wms.inventories': 'Inventory',
  'menu.wms.threshold': 'Threshold',
  'menu.wms.inbound': 'Inbound Notes',
  'menu.wms.outbound': 'Outbound Notes',
  'menu.wms.adjust': 'Adjustments',
  'menu.dispatch': 'Dispatch',
  'menu.dispatch.vehicles': 'Vehicles',
  'menu.dispatch.drivers': 'Drivers',
  'menu.dispatch.carriers': 'Carriers',
  'menu.dispatch.orders': 'Delivery Orders',
  'menu.dispatch.exceptions': 'Exception Work Orders',

  'common.search': 'Search',
  'common.add': 'Add',
  'common.edit': 'Edit',
  'common.delete': 'Delete',
  'common.confirm': 'Confirm',
  'common.cancel': 'Cancel',
  'common.save': 'Save',
  'common.close': 'Close',
  'common.reset': 'Reset',
  'common.preview': 'Preview',
  'common.export': 'Export',
  'common.refresh': 'Refresh',
  'common.loading': 'Loading…',
  'common.noData': 'No data',

  'status.enabled': 'Enabled',
  'status.disabled': 'Disabled',
  'status.success': 'Success',
  'status.failure': 'Failure',

  'login.title': 'Sign In',
  'login.username': 'Username',
  'login.password': 'Password',
  'login.tenant': 'Tenant',
  'login.submit': 'Sign In',
  'login.invalidCredentials': 'Invalid username or password',

  'theme.toggle.toDark': 'Switch to dark',
  'theme.toggle.toLight': 'Switch to light',
};
