-- =============================================================
-- V21: Dispatch permissions (sys_permission, sys_role_permission)
-- Iteration 4.1 PR2
-- =============================================================
-- 背景：
--   sys_permission 现有 id 上限 = 97（V17 wms:threshold:update）。
--   本次新增 26 个 dispatch:* 权限，分配 98–123。
--   列约定沿用 V6 (28–47) + V13 (48–49) + V14 (50) + V17 (51–97)：
--   (id, tenant_id, code, type, name, status, version, deleted)
--   type 全部 'API'（与现有 controller @PreAuthorize hasAuthority(...) 一致）。
--
--   sys_role 现有 id 上限 = 4（V17 WMS_MANAGER）。
--   本次新增 3 个 dispatch 角色：
--     DISPATCH_ADMIN  (id=5)  - 调度管理员
--     DISPATCHER      (id=6)  - 调度员
--     DRIVER          (id=7)  - 司机（手持端 mock）
--
--   注：spec §8 将异常工单权限归到 CUSTOMER_SERVICE 角色，
--       但本期只 seed 3 个 dispatch 角色（spec §10 PR2）；
--       CUSTOMER_SERVICE 留作后续 iteration 接入；
--       本迁移把 exception 5 个 perm 一并绑定到 DISPATCH_ADMIN，
--       作为 dispatch 侧的兜底（待 CUSTOMER_SERVICE 角色 seed 时再调整）。
--
--   sys_role_permission 列：id, role_id, permission_id, tenant_id, created_at
--   列沿用 V6/V13/V14/V17，id 从 200 起，紧接 V17 的 199。
--   UNIQUE KEY (tenant_id, role_id, permission_id) — role_id 不同可重用 perm_id。
-- =============================================================

INSERT INTO sys_permission (id, tenant_id, code, type, name, status, version, deleted) VALUES
-- ----------------------------------------------------------------
-- vehicle  (V21 ids 98–101, 4 perms)
-- ----------------------------------------------------------------
(98,  1, 'dispatch:vehicle:list',     'API', '配送-车辆-列表',      1, 0, 0),
(99,  1, 'dispatch:vehicle:create',   'API', '配送-车辆-创建',      1, 0, 0),
(100, 1, 'dispatch:vehicle:update',   'API', '配送-车辆-更新',      1, 0, 0),
(101, 1, 'dispatch:vehicle:freeze',   'API', '配送-车辆-冻结/解冻', 1, 0, 0),

-- ----------------------------------------------------------------
-- driver  (V21 ids 102–105, 4 perms)
-- ----------------------------------------------------------------
(102, 1, 'dispatch:driver:list',      'API', '配送-司机-列表',      1, 0, 0),
(103, 1, 'dispatch:driver:create',    'API', '配送-司机-创建',      1, 0, 0),
(104, 1, 'dispatch:driver:update',    'API', '配送-司机-更新',      1, 0, 0),
(105, 1, 'dispatch:driver:suspend',   'API', '配送-司机-停用',      1, 0, 0),

-- ----------------------------------------------------------------
-- carrier  (V21 ids 106–108, 3 perms)
-- ----------------------------------------------------------------
(106, 1, 'dispatch:carrier:list',     'API', '配送-承运人-列表',    1, 0, 0),
(107, 1, 'dispatch:carrier:create',   'API', '配送-承运人-创建',    1, 0, 0),
(108, 1, 'dispatch:carrier:update',   'API', '配送-承运人-更新',    1, 0, 0),

-- ----------------------------------------------------------------
-- order  (V21 ids 109–117, 9 perms)
--   list/create/update/cancel  (109-112)  -> DISPATCHER
--   assign/load/depart/arrive/sign (113-117) -> DISPATCHER + DRIVER
-- ----------------------------------------------------------------
(109, 1, 'dispatch:order:list',       'API', '配送-配送单-列表',    1, 0, 0),
(110, 1, 'dispatch:order:create',     'API', '配送-配送单-创建',    1, 0, 0),
(111, 1, 'dispatch:order:update',     'API', '配送-配送单-更新',    1, 0, 0),
(112, 1, 'dispatch:order:cancel',     'API', '配送-配送单-取消',    1, 0, 0),
(113, 1, 'dispatch:order:assign',     'API', '配送-配送单-派车',    1, 0, 0),
(114, 1, 'dispatch:order:load',       'API', '配送-配送单-装载',    1, 0, 0),
(115, 1, 'dispatch:order:depart',     'API', '配送-配送单-出发',    1, 0, 0),
(116, 1, 'dispatch:order:arrive',     'API', '配送-配送单-到达',    1, 0, 0),
(117, 1, 'dispatch:order:sign',       'API', '配送-配送单-签收',    1, 0, 0),

-- ----------------------------------------------------------------
-- exception  (V21 ids 118–122, 5 perms) — 见上文 CUSTOMER_SERVICE 说明
-- ----------------------------------------------------------------
(118, 1, 'dispatch:exception:list',       'API', '配送-异常-列表',     1, 0, 0),
(119, 1, 'dispatch:exception:create',     'API', '配送-异常-创建',     1, 0, 0),
(120, 1, 'dispatch:exception:investigate','API', '配送-异常-调查',     1, 0, 0),
(121, 1, 'dispatch:exception:resolve',    'API', '配送-异常-解决',     1, 0, 0),
(122, 1, 'dispatch:exception:close',      'API', '配送-异常-关闭',     1, 0, 0),

-- ----------------------------------------------------------------
-- dashboard  (V21 id 123, 1 perm)
-- ----------------------------------------------------------------
(123, 1, 'dispatch:dashboard:view',    'API', '配送-运力仪表盘-查看', 1, 0, 0)
;

-- ----------------------------------------------------------------
-- 全部 26 个 dispatch:* 权限授权给 SUPER_ADMIN (role_id = 1)
-- sys_role_permission id 从 200 起，紧接 V17 的 199。
-- ----------------------------------------------------------------
INSERT INTO sys_role_permission (id, role_id, permission_id, tenant_id, created_at) VALUES
(200, 1, 98,  1, NOW()),
(201, 1, 99,  1, NOW()),
(202, 1, 100, 1, NOW()),
(203, 1, 101, 1, NOW()),
(204, 1, 102, 1, NOW()),
(205, 1, 103, 1, NOW()),
(206, 1, 104, 1, NOW()),
(207, 1, 105, 1, NOW()),
(208, 1, 106, 1, NOW()),
(209, 1, 107, 1, NOW()),
(210, 1, 108, 1, NOW()),
(211, 1, 109, 1, NOW()),
(212, 1, 110, 1, NOW()),
(213, 1, 111, 1, NOW()),
(214, 1, 112, 1, NOW()),
(215, 1, 113, 1, NOW()),
(216, 1, 114, 1, NOW()),
(217, 1, 115, 1, NOW()),
(218, 1, 116, 1, NOW()),
(219, 1, 117, 1, NOW()),
(220, 1, 118, 1, NOW()),
(221, 1, 119, 1, NOW()),
(222, 1, 120, 1, NOW()),
(223, 1, 121, 1, NOW()),
(224, 1, 122, 1, NOW()),
(225, 1, 123, 1, NOW())
;

-- =============================================================
-- DISPATCH_ADMIN 角色 (spec §8: 调度管理员 = 全部 dispatch:* 权限)
-- =============================================================
-- sys_role 列沿用 V17: (id, tenant_id, code, name, description, status,
--                       version, deleted)
-- =============================================================
INSERT INTO sys_role (id, tenant_id, code, name, description, status, version, deleted)
VALUES (5, 1, 'DISPATCH_ADMIN', 'DISPATCH_ADMIN', '调度管理员 (demo 角色)', 1, 0, 0)
;

-- DISPATCH_ADMIN 绑定全部 26 个 dispatch:* 权限 (perm_id 98..123)
-- sys_role_permission id 自 226 起，紧接 SUPER_ADMIN 的 225。
INSERT INTO sys_role_permission (id, role_id, permission_id, tenant_id, created_at) VALUES
(226, 5, 98,  1, NOW()),
(227, 5, 99,  1, NOW()),
(228, 5, 100, 1, NOW()),
(229, 5, 101, 1, NOW()),
(230, 5, 102, 1, NOW()),
(231, 5, 103, 1, NOW()),
(232, 5, 104, 1, NOW()),
(233, 5, 105, 1, NOW()),
(234, 5, 106, 1, NOW()),
(235, 5, 107, 1, NOW()),
(236, 5, 108, 1, NOW()),
(237, 5, 109, 1, NOW()),
(238, 5, 110, 1, NOW()),
(239, 5, 111, 1, NOW()),
(240, 5, 112, 1, NOW()),
(241, 5, 113, 1, NOW()),
(242, 5, 114, 1, NOW()),
(243, 5, 115, 1, NOW()),
(244, 5, 116, 1, NOW()),
(245, 5, 117, 1, NOW()),
(246, 5, 118, 1, NOW()),
(247, 5, 119, 1, NOW()),
(248, 5, 120, 1, NOW()),
(249, 5, 121, 1, NOW()),
(250, 5, 122, 1, NOW()),
(251, 5, 123, 1, NOW())
;

-- =============================================================
-- DISPATCHER 角色 (spec §8: 调度员 = 配送单操作 + 仪表盘)
--   绑定：order 全部 9 个 (109..117) + dashboard view (123) = 10 perms
-- =============================================================
INSERT INTO sys_role (id, tenant_id, code, name, description, status, version, deleted)
VALUES (6, 1, 'DISPATCHER', 'DISPATCHER', '调度员 (demo 角色)', 1, 0, 0)
;

-- DISPATCHER 绑定 (perm_id 109..117, 123) — 共 10 行，id 自 252 起。
INSERT INTO sys_role_permission (id, role_id, permission_id, tenant_id, created_at) VALUES
(252, 6, 109, 1, NOW()),
(253, 6, 110, 1, NOW()),
(254, 6, 111, 1, NOW()),
(255, 6, 112, 1, NOW()),
(256, 6, 113, 1, NOW()),
(257, 6, 114, 1, NOW()),
(258, 6, 115, 1, NOW()),
(259, 6, 116, 1, NOW()),
(260, 6, 117, 1, NOW()),
(261, 6, 123, 1, NOW())
;

-- =============================================================
-- DRIVER 角色 (spec §8: 司机 = 配送单现场操作 5 个 perm)
--   绑定：assign / load / depart / arrive / sign (113..117) = 5 perms
-- =============================================================
INSERT INTO sys_role (id, tenant_id, code, name, description, status, version, deleted)
VALUES (7, 1, 'DRIVER', 'DRIVER', '司机 (demo 角色，手持端 mock)', 1, 0, 0)
;

-- DRIVER 绑定 (perm_id 113..117) — 共 5 行，id 自 262 起。
INSERT INTO sys_role_permission (id, role_id, permission_id, tenant_id, created_at) VALUES
(262, 7, 113, 1, NOW()),
(263, 7, 114, 1, NOW()),
(264, 7, 115, 1, NOW()),
(265, 7, 116, 1, NOW()),
(266, 7, 117, 1, NOW())
;

-- =============================================================
-- 备注（与 V20 衔接）:
--   V20 guard_expression 中的角色码：
--     role:dispatcher      -> DISPATCHER (sys_role.code 实际是 'DISPATCHER' 大写)
--     role:dispatch_admin  -> DISPATCH_ADMIN
--     role:driver          -> DRIVER
--     role:customer_service-> (本期未 seed；待后续 iteration 接入)
--
--   V20 EXCEPTION_WORK_ORDER 的 cancel 转换 (guard: dispatch_admin)
--   与本迁移 DISPATCH_ADMIN 角色的 sys_role.code='DISPATCH_ADMIN' 已对齐；
--   investigate/resolve/close/reopen (guard: customer_service) 的兜底
--   已经通过把 exception 5 个 perm 绑给 DISPATCH_ADMIN 处理（运行时
--   guard 解析会拒绝 DISPATCH_ADMIN 触发 investigate 等子状态，
--   仅允许 CUSTOMER_SERVICE 真正操作；建议 PR5 StateMachineEngine
--   引入时把 customer_service role 显式 seed 进来）。
-- =============================================================
