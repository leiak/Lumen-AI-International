/**
 * Lumen Admin — Chinese (Simplified) translations.
 *
 * Keep keys flat (dotted) and ordered by feature area.
 * Missing keys fall back to the key string itself (see LocaleProvider.t).
 */
export const zhCN = {
  // Menu
  'menu.users': '用户管理',
  'menu.roles': '角色管理',
  'menu.permissions': '权限矩阵',
  'menu.dicts': '数据字典',
  'menu.numberRules': '编号规则',
  'menu.notifications': '通知模板',
  'menu.auditLogs': '审计日志',

  // Common
  'common.search': '搜索',
  'common.add': '新增',
  'common.edit': '编辑',
  'common.delete': '删除',
  'common.confirm': '确认',
  'common.cancel': '取消',
  'common.save': '保存',
  'common.close': '关闭',
  'common.reset': '重置',
  'common.preview': '预览',
  'common.export': '导出',
  'common.refresh': '刷新',
  'common.loading': '加载中…',
  'common.noData': '暂无数据',

  // Status
  'status.enabled': '启用',
  'status.disabled': '禁用',
  'status.success': '成功',
  'status.failure': '失败',

  // Login
  'login.title': '登录',
  'login.username': '用户名',
  'login.password': '密码',
  'login.tenant': '租户',
  'login.submit': '登录',
  'login.invalidCredentials': '用户名或密码错误',

  // Theme
  'theme.toggle.toDark': '切换为深色',
  'theme.toggle.toLight': '切换为浅色',
} as const;

export type ZhKey = keyof typeof zhCN;
