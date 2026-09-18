-- =============================================================
-- V6: 补齐 RBAC 权限种子 (修复 Dict / Notification Templates / Currency / UoM CRUD 的 403)
-- =============================================================
-- 背景: V2 只种了 27 个权限，但 controllers 引用 46 个。
--      缺: dict:* (5), notification_template:list/view/create/update/delete (5),
--         currency:* (5), uom:* (5)
-- 新 id 从 28 开始，保留 V2 已分配的 1-27 段。
-- 全部授权给 SUPER_ADMIN (role_id = 1)。
--
-- 注意：列列定义沿用 V2 的 (id, tenant_id, code, type, name, status, version, deleted)
--       因为 sys_permission 的实际列为 id, tenant_id, parent_id, type, code, name,
--       path, sort_order, status, created_at, updated_at, deleted, version，
--       不含 resource/action/description（V2 里压根没建这些列）。

INSERT INTO sys_permission (id, tenant_id, code, type, name, status, version, deleted) VALUES
-- dict (V6 ids 28-32)
(28, 1, 'dict:list',                  'API', '字典-列表',     1, 0, 0),
(29, 1, 'dict:view',                  'API', '字典-查看',     1, 0, 0),
(30, 1, 'dict:create',                'API', '字典-创建',     1, 0, 0),
(31, 1, 'dict:update',                'API', '字典-更新',     1, 0, 0),
(32, 1, 'dict:delete',                'API', '字典-删除',     1, 0, 0),

-- notification_template (V6 ids 33-37)
(33, 1, 'notification_template:list',   'API', '通知模板-列表', 1, 0, 0),
(34, 1, 'notification_template:view',   'API', '通知模板-查看', 1, 0, 0),
(35, 1, 'notification_template:create', 'API', '通知模板-创建', 1, 0, 0),
(36, 1, 'notification_template:update', 'API', '通知模板-更新', 1, 0, 0),
(37, 1, 'notification_template:delete', 'API', '通知模板-删除', 1, 0, 0),

-- currency (V6 ids 38-42)
(38, 1, 'currency:list',              'API', '币种-列表',     1, 0, 0),
(39, 1, 'currency:view',              'API', '币种-查看',     1, 0, 0),
(40, 1, 'currency:create',            'API', '币种-创建',     1, 0, 0),
(41, 1, 'currency:update',            'API', '币种-更新',     1, 0, 0),
(42, 1, 'currency:delete',            'API', '币种-删除',     1, 0, 0),

-- uom (V6 ids 43-47)
(43, 1, 'uom:list',                   'API', '计量单位-列表', 1, 0, 0),
(44, 1, 'uom:view',                   'API', '计量单位-查看', 1, 0, 0),
(45, 1, 'uom:create',                 'API', '计量单位-创建', 1, 0, 0),
(46, 1, 'uom:update',                 'API', '计量单位-更新', 1, 0, 0),
(47, 1, 'uom:delete',                 'API', '计量单位-删除', 1, 0, 0)
;

-- 全部授权给 SUPER_ADMIN (role_id = 1)
-- sys_role_permission 列: id, role_id, permission_id, tenant_id, created_at
-- 沿用 V2 的 id 起点，紧接 27，从 28 开始到 47。
INSERT INTO sys_role_permission (id, role_id, permission_id, tenant_id, created_at) VALUES
(28, 1, 28, 1, NOW()),
(29, 1, 29, 1, NOW()),
(30, 1, 30, 1, NOW()),
(31, 1, 31, 1, NOW()),
(32, 1, 32, 1, NOW()),
(33, 1, 33, 1, NOW()),
(34, 1, 34, 1, NOW()),
(35, 1, 35, 1, NOW()),
(36, 1, 36, 1, NOW()),
(37, 1, 37, 1, NOW()),
(38, 1, 38, 1, NOW()),
(39, 1, 39, 1, NOW()),
(40, 1, 40, 1, NOW()),
(41, 1, 41, 1, NOW()),
(42, 1, 42, 1, NOW()),
(43, 1, 43, 1, NOW()),
(44, 1, 44, 1, NOW()),
(45, 1, 45, 1, NOW()),
(46, 1, 46, 1, NOW()),
(47, 1, 47, 1, NOW())
;