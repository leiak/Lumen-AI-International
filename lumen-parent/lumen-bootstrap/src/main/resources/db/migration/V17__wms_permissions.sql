-- =============================================================
-- V17: WMS permissions (sys_permission, sys_role_permission)
-- (Renumbered V16→V17 because V16 is wms_seed, V15 is wms_schema,
--  V14 is audit_log_query_permission)
-- =============================================================
-- 背景：
--   sys_permission 现有 id 上限 = 50（V14 audit:list）。
--   本次新增 47 个 wms:* 权限，分配 51–97。
--   列约定沿用 V6 (28–47) + V13 (48–49) + V14 (50)：
--   (id, tenant_id, code, type, name, status, version, deleted)
--   type 全部 'API'（与现有 controller @PreAuthorize hasAuthority(...) 一致）。
--   全部授权给 SUPER_ADMIN (role_id = 1) —— 与 V6 / V13 / V14 保持一致。
-- =============================================================

INSERT INTO sys_permission (id, tenant_id, code, type, name, status, version, deleted) VALUES
-- ----------------------------------------------------------------
-- warehouse  (V17 ids 51–55, 5 perms)
-- ----------------------------------------------------------------
(51, 1, 'wms:warehouse:list',    'API', '仓库-列表',     1, 0, 0),
(52, 1, 'wms:warehouse:create',  'API', '仓库-创建',     1, 0, 0),
(53, 1, 'wms:warehouse:update',  'API', '仓库-更新',     1, 0, 0),
(54, 1, 'wms:warehouse:delete',  'API', '仓库-删除',     1, 0, 0),
(55, 1, 'wms:warehouse:freeze',  'API', '仓库-冻结/解冻',1, 0, 0),

-- ----------------------------------------------------------------
-- location  (V17 ids 56–61, 6 perms)
-- ----------------------------------------------------------------
(56, 1, 'wms:location:list',     'API', '库位-列表',     1, 0, 0),
(57, 1, 'wms:location:create',   'API', '库位-创建',     1, 0, 0),
(58, 1, 'wms:location:update',   'API', '库位-更新',     1, 0, 0),
(59, 1, 'wms:location:delete',   'API', '库位-删除',     1, 0, 0),
(60, 1, 'wms:location:freeze',   'API', '库位-冻结/解冻',1, 0, 0),
(61, 1, 'wms:location:disable',  'API', '库位-停用',     1, 0, 0),

-- ----------------------------------------------------------------
-- item  (V17 ids 62–65, 4 perms)
-- ----------------------------------------------------------------
(62, 1, 'wms:item:list',         'API', '货品-列表',     1, 0, 0),
(63, 1, 'wms:item:create',       'API', '货品-创建',     1, 0, 0),
(64, 1, 'wms:item:update',       'API', '货品-更新',     1, 0, 0),
(65, 1, 'wms:item:delete',       'API', '货品-删除',     1, 0, 0),

-- ----------------------------------------------------------------
-- customer  (V17 ids 66–69, 4 perms)
-- ----------------------------------------------------------------
(66, 1, 'wms:customer:list',     'API', '客户-列表',     1, 0, 0),
(67, 1, 'wms:customer:create',   'API', '客户-创建',     1, 0, 0),
(68, 1, 'wms:customer:update',   'API', '客户-更新',     1, 0, 0),
(69, 1, 'wms:customer:delete',   'API', '客户-删除',     1, 0, 0),

-- ----------------------------------------------------------------
-- inbound  (V17 ids 70–77, 8 perms)
-- ----------------------------------------------------------------
(70, 1, 'wms:inbound:list',      'API', '入库单-列表',   1, 0, 0),
(71, 1, 'wms:inbound:create',    'API', '入库单-创建',   1, 0, 0),
(72, 1, 'wms:inbound:update',    'API', '入库单-更新',   1, 0, 0),
(73, 1, 'wms:inbound:submit',    'API', '入库单-提交',   1, 0, 0),
(74, 1, 'wms:inbound:approve',   'API', '入库单-审批',   1, 0, 0),
(75, 1, 'wms:inbound:putaway',   'API', '入库单-上架',   1, 0, 0),
(76, 1, 'wms:inbound:complete',  'API', '入库单-完成',   1, 0, 0),
(77, 1, 'wms:inbound:cancel',    'API', '入库单-取消',   1, 0, 0),

-- ----------------------------------------------------------------
-- outbound  (V17 ids 78–86, 9 perms)
-- ----------------------------------------------------------------
(78, 1, 'wms:outbound:list',     'API', '出库单-列表',   1, 0, 0),
(79, 1, 'wms:outbound:create',   'API', '出库单-创建',   1, 0, 0),
(80, 1, 'wms:outbound:update',   'API', '出库单-更新',   1, 0, 0),
(81, 1, 'wms:outbound:submit',   'API', '出库单-提交',   1, 0, 0),
(82, 1, 'wms:outbound:approve',  'API', '出库单-审批',   1, 0, 0),
(83, 1, 'wms:outbound:pick',     'API', '出库单-拣货',   1, 0, 0),
(84, 1, 'wms:outbound:ship',     'API', '出库单-发货',   1, 0, 0),
(85, 1, 'wms:outbound:complete', 'API', '出库单-完成',   1, 0, 0),
(86, 1, 'wms:outbound:cancel',   'API', '出库单-取消',   1, 0, 0),

-- ----------------------------------------------------------------
-- adjust  (V17 ids 87–93, 7 perms)
-- ----------------------------------------------------------------
(87, 1, 'wms:adjust:list',       'API', '调整单-列表',   1, 0, 0),
(88, 1, 'wms:adjust:create',     'API', '调整单-创建',   1, 0, 0),
(89, 1, 'wms:adjust:update',     'API', '调整单-更新',   1, 0, 0),
(90, 1, 'wms:adjust:submit',     'API', '调整单-提交',   1, 0, 0),
(91, 1, 'wms:adjust:approve',    'API', '调整单-审批',   1, 0, 0),
(92, 1, 'wms:adjust:execute',    'API', '调整单-执行',   1, 0, 0),
(93, 1, 'wms:adjust:cancel',     'API', '调整单-取消',   1, 0, 0),

-- ----------------------------------------------------------------
-- inventory  (V17 ids 94–95, 2 perms)
-- ----------------------------------------------------------------
(94, 1, 'wms:inventory:list',    'API', '库存-查询',     1, 0, 0),
(95, 1, 'wms:inventory:summary', 'API', '库存-汇总',     1, 0, 0),

-- ----------------------------------------------------------------
-- threshold  (V17 ids 96–97, 2 perms)
-- ----------------------------------------------------------------
(96, 1, 'wms:threshold:get',     'API', '阈值-读取',     1, 0, 0),
(97, 1, 'wms:threshold:update',  'API', '阈值-更新',     1, 0, 0)
;

-- ----------------------------------------------------------------
-- 全部授权给 SUPER_ADMIN (role_id = 1)
-- sys_role_permission 列: id, role_id, permission_id, tenant_id, created_at
-- 沿用 V6 的 id 起点，紧接 50，从 51 开始到 97。
-- ----------------------------------------------------------------
INSERT INTO sys_role_permission (id, role_id, permission_id, tenant_id, created_at) VALUES
(51,  1, 51,  1, NOW()),
(52,  1, 52,  1, NOW()),
(53,  1, 53,  1, NOW()),
(54,  1, 54,  1, NOW()),
(55,  1, 55,  1, NOW()),
(56,  1, 56,  1, NOW()),
(57,  1, 57,  1, NOW()),
(58,  1, 58,  1, NOW()),
(59,  1, 59,  1, NOW()),
(60,  1, 60,  1, NOW()),
(61,  1, 61,  1, NOW()),
(62,  1, 62,  1, NOW()),
(63,  1, 63,  1, NOW()),
(64,  1, 64,  1, NOW()),
(65,  1, 65,  1, NOW()),
(66,  1, 66,  1, NOW()),
(67,  1, 67,  1, NOW()),
(68,  1, 68,  1, NOW()),
(69,  1, 69,  1, NOW()),
(70,  1, 70,  1, NOW()),
(71,  1, 71,  1, NOW()),
(72,  1, 72,  1, NOW()),
(73,  1, 73,  1, NOW()),
(74,  1, 74,  1, NOW()),
(75,  1, 75,  1, NOW()),
(76,  1, 76,  1, NOW()),
(77,  1, 77,  1, NOW()),
(78,  1, 78,  1, NOW()),
(79,  1, 79,  1, NOW()),
(80,  1, 80,  1, NOW()),
(81,  1, 81,  1, NOW()),
(82,  1, 82,  1, NOW()),
(83,  1, 83,  1, NOW()),
(84,  1, 84,  1, NOW()),
(85,  1, 85,  1, NOW()),
(86,  1, 86,  1, NOW()),
(87,  1, 87,  1, NOW()),
(88,  1, 88,  1, NOW()),
(89,  1, 89,  1, NOW()),
(90,  1, 90,  1, NOW()),
(91,  1, 91,  1, NOW()),
(92,  1, 92,  1, NOW()),
(93,  1, 93,  1, NOW()),
(94,  1, 94,  1, NOW()),
(95,  1, 95,  1, NOW()),
(96,  1, 96,  1, NOW()),
(97,  1, 97,  1, NOW())
;

-- =============================================================
-- WMS_ADMIN 角色 (spec §9: demo 角色，绑定全部 47 个 wms:* 权限)
-- =============================================================
-- sys_role 列 (V2): id, tenant_id, code, name, description, data_scope,
--                   status, created_at, updated_at, deleted, version
-- 列沿用 V2 的 INSERT 形态，仅补 description。
-- =============================================================
INSERT INTO sys_role (id, tenant_id, code, name, description, status, version, deleted)
VALUES (2, 1, 'WMS_ADMIN', 'WMS_ADMIN', 'WMS 管理员 (demo 角色)', 1, 0, 0)
;

-- ----------------------------------------------------------------
-- WMS_ADMIN 绑定全部 47 个 wms:* 权限 (permission_id 51..97)
-- sys_role_permission id 从 98 起，紧接 SUPER_ADMIN 的 97。
-- UNIQUE KEY (tenant_id, role_id, permission_id) 不会冲突，
--   因为 (1, 51..97) 已存在但 (2, 51..97) 是新组合。
-- ----------------------------------------------------------------
INSERT INTO sys_role_permission (id, role_id, permission_id, tenant_id, created_at) VALUES
(98,  2, 51,  1, NOW()),
(99,  2, 52,  1, NOW()),
(100, 2, 53,  1, NOW()),
(101, 2, 54,  1, NOW()),
(102, 2, 55,  1, NOW()),
(103, 2, 56,  1, NOW()),
(104, 2, 57,  1, NOW()),
(105, 2, 58,  1, NOW()),
(106, 2, 59,  1, NOW()),
(107, 2, 60,  1, NOW()),
(108, 2, 61,  1, NOW()),
(109, 2, 62,  1, NOW()),
(110, 2, 63,  1, NOW()),
(111, 2, 64,  1, NOW()),
(112, 2, 65,  1, NOW()),
(113, 2, 66,  1, NOW()),
(114, 2, 67,  1, NOW()),
(115, 2, 68,  1, NOW()),
(116, 2, 69,  1, NOW()),
(117, 2, 70,  1, NOW()),
(118, 2, 71,  1, NOW()),
(119, 2, 72,  1, NOW()),
(120, 2, 73,  1, NOW()),
(121, 2, 74,  1, NOW()),
(122, 2, 75,  1, NOW()),
(123, 2, 76,  1, NOW()),
(124, 2, 77,  1, NOW()),
(125, 2, 78,  1, NOW()),
(126, 2, 79,  1, NOW()),
(127, 2, 80,  1, NOW()),
(128, 2, 81,  1, NOW()),
(129, 2, 82,  1, NOW()),
(130, 2, 83,  1, NOW()),
(131, 2, 84,  1, NOW()),
(132, 2, 85,  1, NOW()),
(133, 2, 86,  1, NOW()),
(134, 2, 87,  1, NOW()),
(135, 2, 88,  1, NOW()),
(136, 2, 89,  1, NOW()),
(137, 2, 90,  1, NOW()),
(138, 2, 91,  1, NOW()),
(139, 2, 92,  1, NOW()),
(140, 2, 93,  1, NOW()),
(141, 2, 94,  1, NOW()),
(142, 2, 95,  1, NOW()),
(143, 2, 96,  1, NOW()),
(144, 2, 97,  1, NOW())
;

-- =============================================================
-- WMS_CLERK 角色 (spec §9: demo 角色 — operational, 无审批权限)
-- =============================================================
-- sys_role 列 (V2): id, tenant_id, code, name, description, data_scope,
--                   status, created_at, updated_at, deleted, version
-- 列沿用 V17 WMS_ADMIN 的 INSERT 形态，仅补 description。
-- name 沿用 WMS_ADMIN 的同义重复惯例（code/name 一致），
-- description 保留中文以便日志/列表可读。
-- =============================================================
INSERT INTO sys_role (id, tenant_id, code, name, description, status, version, deleted)
VALUES (3, 1, 'WMS_CLERK', 'WMS_CLERK', 'WMS 仓库文员 (demo 角色)', 1, 0, 0)
;

-- ----------------------------------------------------------------
-- WMS_CLERK 绑定: 操作权限 (per spec §9: 无 delete/approve/freeze/disable)
--   warehouse:list (51)
--   location:list (56)
--   item:list,create,update (62,63,64)
--   customer:list,create,update (66,67,68)
--   inbound:list,create,update,submit,putaway,complete,cancel (70–73,75,76,77)
--   outbound:list,create,update,submit,pick,ship,complete,cancel (78–81,83–86)
--   adjust:list,create,update,submit,execute,cancel (87–90,92,93)
--   inventory:list,summary (94,95)
-- 共 31 行。sys_role_permission id 自 145 起，紧接 WMS_ADMIN 的 144。
-- UNIQUE KEY (tenant_id, role_id, permission_id) 不会冲突，
--   因为 role_id=3 是新组合。
-- ----------------------------------------------------------------
INSERT INTO sys_role_permission (id, role_id, permission_id, tenant_id, created_at) VALUES
(145, 3, 51,  1, NOW()),
(146, 3, 56,  1, NOW()),
(147, 3, 62,  1, NOW()),
(148, 3, 63,  1, NOW()),
(149, 3, 64,  1, NOW()),
(150, 3, 66,  1, NOW()),
(151, 3, 67,  1, NOW()),
(152, 3, 68,  1, NOW()),
(153, 3, 70,  1, NOW()),
(154, 3, 71,  1, NOW()),
(155, 3, 72,  1, NOW()),
(156, 3, 73,  1, NOW()),
(157, 3, 75,  1, NOW()),
(158, 3, 76,  1, NOW()),
(159, 3, 77,  1, NOW()),
(160, 3, 78,  1, NOW()),
(161, 3, 79,  1, NOW()),
(162, 3, 80,  1, NOW()),
(163, 3, 81,  1, NOW()),
(164, 3, 83,  1, NOW()),
(165, 3, 84,  1, NOW()),
(166, 3, 85,  1, NOW()),
(167, 3, 86,  1, NOW()),
(168, 3, 87,  1, NOW()),
(169, 3, 88,  1, NOW()),
(170, 3, 89,  1, NOW()),
(171, 3, 90,  1, NOW()),
(172, 3, 92,  1, NOW()),
(173, 3, 93,  1, NOW()),
(174, 3, 94,  1, NOW()),
(175, 3, 95,  1, NOW())
;

-- =============================================================
-- WMS_MANAGER 角色 (spec §9: demo 角色 — 审批 + 冻结 + 阈值更新)
-- =============================================================
-- WMS_MANAGER 拥有所有 wms:warehouse:* / wms:location:* 操作，
--   以及三单 (inbound/outbound/adjust) 的 approve/cancel 权限，
--   加上 threshold:get/update 与 inventory 查询。
-- 共 24 行绑定。sys_role_permission id 自 176 起。
-- =============================================================
INSERT INTO sys_role (id, tenant_id, code, name, description, status, version, deleted)
VALUES (4, 1, 'WMS_MANAGER', 'WMS_MANAGER', 'WMS 仓库主管 (demo 角色)', 1, 0, 0)
;

INSERT INTO sys_role_permission (id, role_id, permission_id, tenant_id, created_at) VALUES
(176, 4, 51,  1, NOW()),
(177, 4, 52,  1, NOW()),
(178, 4, 53,  1, NOW()),
(179, 4, 54,  1, NOW()),
(180, 4, 55,  1, NOW()),
(181, 4, 56,  1, NOW()),
(182, 4, 57,  1, NOW()),
(183, 4, 58,  1, NOW()),
(184, 4, 59,  1, NOW()),
(185, 4, 60,  1, NOW()),
(186, 4, 61,  1, NOW()),
(187, 4, 70,  1, NOW()),
(188, 4, 74,  1, NOW()),
(189, 4, 77,  1, NOW()),
(190, 4, 78,  1, NOW()),
(191, 4, 82,  1, NOW()),
(192, 4, 86,  1, NOW()),
(193, 4, 87,  1, NOW()),
(194, 4, 91,  1, NOW()),
(195, 4, 93,  1, NOW()),
(196, 4, 94,  1, NOW()),
(197, 4, 95,  1, NOW()),
(198, 4, 96,  1, NOW()),
(199, 4, 97,  1, NOW())
;

-- =============================================================
-- 备注（与 V16 衔接）:
--   guard_expression 中以 'role:wms_clerk' / 'role:wms_manager' / 'role:wms_admin'
--   形式引用了三类 demo 角色。本迁移新增 sys_role 行 (WMS_CLERK, WMS_MANAGER)
--   后，runtime guard（待实现）可按 lowercase code 进行 sys_role 命中查询 —
--   实现期应注意 sys_role.code 实际为 'WMS_CLERK' / 'WMS_MANAGER'，
--   需在比较函数中统一做大小写规整化（详见 lumen-wms StateMachineEngine）。
-- =============================================================