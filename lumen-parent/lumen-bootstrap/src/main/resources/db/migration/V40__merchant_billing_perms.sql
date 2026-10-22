-- =============================================================
-- V40: 商家门户对账/付款/调账单 3 个权限码 (4.5C-2)
--
-- 背景:
--   4.5C-2 新增 PortalInvoiceController / PortalPaymentController /
--   PortalAdjustmentController, 加上 PortalReconController 的 POST /dispute,
--   一共要用到 5 个 authority。其中 customer:invoice:view (id 162) 与
--   customer:recon:view (id 163) V33 已 seed, 本次只补缺的那 3 个。
--
--   为什么不重插已存在的 code:
--     sys_permission 上有 UNIQUE KEY uk_perm_tenant_code (tenant_id, code),
--     而 V33 的 INSERT 写的是 ON DUPLICATE KEY UPDATE。用新 id 重插已存在的
--     code, 撞的是这个唯一键而不是主键 —— MySQL 会就地更新已存在的那一行,
--     新 id 永远不会被创建。V36 正是这么踩出两条悬空 sys_role_permission 的,
--     V37 事后擦的屁股。**不要重复这个错误。**
--
-- id 段:
--   sys_permission 末态最大 id = 168 (V36 的 customer:order:cancel)。
--     注意 166/167 从未真正创建 (撞唯一键), V37 只删了对应的绑定行。
--     本次从 169 起。
--   sys_role_permission 末态最大 id = 330 (328/329 已被 V37 删除),
--     本次从 331 起。
--
--   sort_order: 沿用门户段已用到的 1100..1109 之后接 1110..1112,
--   不与 V33 (1100..1106) / V36 (1107..1109) 重复。
--   (sys_permission.sort_order 上没有唯一约束, 重复也不会报错, 但重复会让
--    后台权限树的排序变成不稳定序 —— 没有理由制造这种歧义。)
--
--   不动 V33 / V36 / V37 本身 —— Flyway 已记录其 checksum。
-- =============================================================

-- ------------------------------ 1. 3 个新权限码 ------------------------------
INSERT INTO sys_permission (id, tenant_id, parent_id, code, type, name, path, sort_order, status, version, deleted, created_at, updated_at) VALUES
  (169, 1, NULL, 'customer:payment:view',      'API', '客户看付款记录',   '/portal/payment',    1110, 1, 0, 0, NOW(), NOW()),
  (170, 1, NULL, 'customer:adjustment:view',   'API', '客户看自己的调账单', '/portal/adjustment', 1111, 1, 0, 0, NOW(), NOW()),
  (171, 1, NULL, 'customer:adjustment:create', 'API', '客户提对账异议',     '/portal/adjustment', 1112, 1, 0, 0, NOW(), NOW())
ON DUPLICATE KEY UPDATE
    name = VALUES(name),
    path = VALUES(path),
    sort_order = VALUES(sort_order);

-- ------------------------------ 2. 绑 customer 角色 (id 14) ------------------------------
INSERT INTO sys_role_permission (id, role_id, permission_id, tenant_id, created_at) VALUES
  (331, 14, 169, 1, NOW()),
  (332, 14, 170, 1, NOW()),
  (333, 14, 171, 1, NOW())
ON DUPLICATE KEY UPDATE
    role_id = VALUES(role_id),
    permission_id = VALUES(permission_id);