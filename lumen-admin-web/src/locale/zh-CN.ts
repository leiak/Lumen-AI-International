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
  'menu.wms': '仓储管理',
  'menu.wms.warehouses': '仓库',
  'menu.wms.locations': '库位',
  'menu.wms.items': '货品',
  'menu.wms.customers': '客户',
  'menu.wms.inventories': '库存',
  'menu.wms.threshold': '审批阈值',
  'menu.wms.inbound': '入库单',
  'menu.wms.outbound': '出库单',
  'menu.wms.adjust': '调整单',
  'menu.dispatch': '配送调度',
  'menu.dispatch.vehicles': '车队',
  'menu.dispatch.drivers': '司机',
  'menu.dispatch.carriers': '承运人',
  'menu.dispatch.orders': '配送单',
  'menu.dispatch.exceptions': '异常工单',
  'menu.dispatch.dashboard': '仪表盘',
  'menu.cs': '客服中心',
  'menu.cs.slaConfigs': 'SLA 配置',
  'menu.cs.calendar': '业务时间',
  'menu.cs.holidays': '节假日',
  'menu.cs.exceptions': '异常工单',
  'menu.finance': '财务',
  'menu.finance.invoices': '应收/应付单据',
  'menu.finance.rules': '计费规则',

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
