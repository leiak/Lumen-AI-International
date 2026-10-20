-- =============================================================
-- V37: 清理 V36 block 3 的两条悬空 sys_role_permission 行
--
-- 背景 (4.5B.4 集成测试阶段发现):
--   V36 想给客户门户加 3 个权限, 用了 id 166/167/168。但 V33 建门户角色时
--   已经建过其中两个:
--       id 160 = customer:order:create
--       id 161 = customer:order:view
--   而 sys_permission 上有 UNIQUE KEY uk_perm_tenant_code (tenant_id, code) ——
--   V36 那句 `INSERT ... (166, ..., 'customer:order:create') ON DUPLICATE KEY UPDATE`
--   撞上的不是主键而是这个唯一键, 于是 MySQL 把已存在的 160 行就地更新成了
--   V36 的 name/path/sort_order。id 166/167 从来没被创建出来。
--
--   紧接着的 sys_role_permission INSERT (id 328/329) 指向 166/167 ——
--   而这两条 permission_id 在 sys_permission 里查无此行, 成了悬空引用。
--   (该表没有外键约束, 所以不会报错, 只会安静地指向不存在的权限。)
--
-- 影响面:
--   功能上没有 — 客户角色 (id 14) 末态权限是 159..165 + 168 共 8 条, 与
--   PortalAuthService.CUSTOMER_PERMS 硬编码列表一致, 门户全部端点鉴权正常。
--   只是后台角色-权限管理界面里会多出两条空白行, 且 V36 的注释
--   ("id 上限 165 → 本次 +3 → 上限 168") 会误导后来人。
--
-- 处理:
--   删掉这两条悬空绑定。168 (`customer:order:cancel`) 是 V36 真正新建的权限,
--   绑定行 330 有效, 保留。
--
--   不动 V36 本身 —— Flyway 已记录其 checksum, 改动会导致校验失败。
-- =============================================================

DELETE rp
FROM sys_role_permission rp
LEFT JOIN sys_permission p ON p.id = rp.permission_id
WHERE p.id IS NULL
  AND rp.role_id = 14;