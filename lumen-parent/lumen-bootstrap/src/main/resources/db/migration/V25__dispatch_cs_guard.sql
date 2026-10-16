-- =============================================================
-- V25 — Iteration 4.2 PR5: cs_* 角色 + cs:* 权限 + dispatch_admin 解绑
-- =============================================================
-- 背景:
--   Iteration 4.1 PR4 起的异常工单 (/api/v1/dispatch/exceptions) 由
--   dispatch_admin 兜底, 因为 spec 把异常处理权划给 customer_service 角色,
--   但 4.1 系列未 seed cs_* 角色 — V21 把 dispatch:exception:* 5 个 perm 一并
--   绑给 dispatch_admin 充当临时兜底.
--
--   4.2 PR1 (lumen-cs 上线) 引入了 V24 cs schema + 9 默认 SLA config;
--   4.2 PR5 (本迁移) 把异常工单处理权从 dispatch_admin 正式切到 cs_*:
--     1. 3 个 cs_* 角色 (customer_service / cs_supervisor / cs_manager)
--     2. 5 个 cs:* 权限 (id 124..128):
--        - cs:sla:config / cs:calendar:config / cs:holiday:config (3 config)
--        - cs:exception:view / cs:exception:respond (2 exception)
--     3. 角色权限绑定 (sys_role_permission id 267..283):
--        - customer_service (id=8):  cs:exception:view + cs:exception:respond
--        - cs_supervisor    (id=9):  同上
--        - cs_manager       (id=10): 全部 5 个 cs:*
--     4. dispatch_admin (role_id=5) 全量解绑 dispatch:exception:* 5 个 perm
--        (id 118..122): 异常工单处理权完全切到 cs_* 三角色, dispatch_admin
--        不再保留 list 兜底 — 任何拿不到 cs:exception:view 的角色都不能
--        访问 /api/v1/dispatch/exceptions (任何 path), 即使持有其他
--        角色 (例如 SUPER_ADMIN, 因 V21 拿到 dispatch:exception:*) 仍
--        可访问, 因为 controller 用 hasAnyAuthority 同时接受新旧 perm
--        code (见 DeliveryExceptionController 4.2 PR5 注释).
--
--   关于 t_state_transition.guard_expression:
--     V20 在 EXCEPTION_WORK_ORDER 4 个 transition (investigate / resolve /
--     close / reopen) 的 guard 写了 'role:customer_service', 但当前
--     StateMachineEngine 还没接 guard_expression 解析 (4.1.x-PR2 留的
--     documentary 占位字段, 真正迁移路径不依赖它 — V21 注释里有 caveat).
--     4.2 PR5 不更新 guard_expression, 因为更新不会被 runtime 读到;
--     真正的访问控制走 @PreAuthorize( hasAnyAuthority(...) ).
--     若后续 StateMachineEngine 引入 guard 解析, 届时再用一个独立迁移
--     把这 4 个 transition 的 guard 统一改为 OR 表达式.
--     cancel (guard: dispatch_admin) 不变 — cancel 是 admin 强权, 兜底.
--
--   sys_role 现有 id 上限 = 7 (V21 DRIVER); 本次新增 8..10。
--   sys_permission 现有 id 上限 = 123 (V21 dashboard view); 本次新增 124..128。
--   sys_role_permission 现有 id 上限 = 266 (V21 DRIVER 末条); 本次新增 267..275。
-- =============================================================

-- ----------------------------------------------------------------
-- 1. cs_* 角色 seed (3 行, id 8..10)
--   sys_role 列 (V17/V21): (id, tenant_id, code, name, description, status, version, deleted)
--   tenant_id=1 与 V21 一致 (RBAC 表是 tenant-scoped);
--   sys_role 唯一键为 (tenant_id, code).
--   code 用 lowercase (sys_role.code 列定义允许大小写混存, V21 是 UPPERCASE,
--   但 Plan 与 pr 文档显式用 lowercase, 与 V20 guard_expression 字符串惯例保持一致).
-- ----------------------------------------------------------------
INSERT INTO sys_role (id, tenant_id, code, name, description, status, version, deleted)
VALUES
(8,  1, 'customer_service', '客服专员',     'L1 异常工单处理 (升级前)',          1, 0, 0),
(9,  1, 'cs_supervisor',    '客服主管',     'L2 异常工单处理 (升级后)',          1, 0, 0),
(10, 1, 'cs_manager',       '客服经理',     'L3 异常工单处理 + 全部 cs: 配置',   1, 0, 0)
ON DUPLICATE KEY UPDATE name = VALUES(name), description = VALUES(description)
;

-- ----------------------------------------------------------------
-- 2. 5 个 cs:* 权限 (id 124..128)
--   sys_permission 列 (V21): (id, tenant_id, code, type, name, path,
--                             sort_order, status, version, deleted)
--   type 全部 'API' (与现有 controller @PreAuthorize hasAuthority(...) 一致);
--   path / sort_order 用于前端菜单按 sort_order 排序 (sys_permission schema
--   实际有这 2 列, 见 V2 sys_permission 定义).
-- ----------------------------------------------------------------
INSERT INTO sys_permission (id, tenant_id, code, type, name, path, sort_order, status, version, deleted)
VALUES
(124, 1, 'cs:sla:config',        'API', 'SLA 配置',     '/cs/sla-configs',        901, 1, 0, 0),
(125, 1, 'cs:calendar:config',   'API', '业务时间',     '/cs/calendar',           902, 1, 0, 0),
(126, 1, 'cs:holiday:config',    'API', '节假日',       '/cs/holidays',           903, 1, 0, 0),
(127, 1, 'cs:exception:view',    'API', '异常工单查看', '/cs/exceptions',         904, 1, 0, 0),
(128, 1, 'cs:exception:respond', 'API', '异常工单处理', '/api/v1/cs/exceptions',  905, 1, 0, 0)
ON DUPLICATE KEY UPDATE name = VALUES(name), path = VALUES(path), sort_order = VALUES(sort_order)
;

-- ----------------------------------------------------------------
-- 3. 角色权限绑定 (sys_role_permission id 267..275, 共 9 行)
--   customer_service (id=8)  -> cs:exception:view + cs:exception:respond (2 行)
--   cs_supervisor    (id=9)  -> 同上 (2 行)
--   cs_manager       (id=10) -> 全部 5 个 cs:* (5 行)
--   sys_role_permission 列 (V21): (id, role_id, permission_id, tenant_id, created_at)
-- ----------------------------------------------------------------
INSERT INTO sys_role_permission (id, role_id, permission_id, tenant_id, created_at) VALUES
-- customer_service -> cs:exception:view + respond
(267, 8,  127, 1, NOW()),
(268, 8,  128, 1, NOW()),
-- cs_supervisor -> cs:exception:view + respond
(269, 9,  127, 1, NOW()),
(270, 9,  128, 1, NOW()),
-- cs_manager -> 全部 5 cs:* (id 124..128)
(271, 10, 124, 1, NOW()),
(272, 10, 125, 1, NOW()),
(273, 10, 126, 1, NOW()),
(274, 10, 127, 1, NOW()),
(275, 10, 128, 1, NOW())
;

-- ----------------------------------------------------------------
-- 4. dispatch_admin (role_id=5) 解绑 dispatch:exception:* 5 个 perm
--   (id 118..122) — V21 seed 把这 5 个 perm 全绑给 dispatch_admin, PR5 把
--   异常处理权完全切到 cs_*, 故解绑全部 5 个 (不保留 list 兜底):
--     - list       (118)
--     - create     (119)
--     - investigate (120)
--     - resolve    (121)
--     - close      (122)
--   解绑后 dispatch_admin 与异常工单接口再无关系 — 任何拿不到
--   cs:exception:view 的角色都拿不到 list 视图.
--
--   注意: SUPER_ADMIN (role_id=1) 仍通过 V21 持有 dispatch:exception:*
--   (id 220..224), 因此 SUPER_ADMIN 仍可访问 (controller 用
--   hasAnyAuthority 同时接受 dispatch:exception:list 与 cs:exception:view).
-- ----------------------------------------------------------------
DELETE FROM sys_role_permission
WHERE role_id = 5
  AND tenant_id = 1
  AND permission_id IN (118, 119, 120, 121, 122)
;

-- =============================================================
-- 备注:
--   - 本迁移改 4 张表:
--       sys_role             +3 行 (id 8..10 cs_*)
--       sys_permission       +5 行 (id 124..128 cs:*)
--       sys_role_permission  +9 行 (id 267..275 cs_* 绑定)
--                            -5 行 (id 246..250 dispatch_admin 解绑)
--       t_state_transition   不动 (guard_expression 当前 runtime 不解析, 留 documentary)
--   - 旧 V20/V21 备注里的 'role:customer_service' guard 现在仍是 documentary,
--     本次不更新 — 详见文件头注释 "关于 t_state_transition.guard_expression".
--   - cs_manager 拿到的是 cs:exception:view + cs:exception:respond + 3 个
--     配置 perm, 加上 V25 之前已有的角色关系, 形成完整的 L3 处理人 + 配
--     置管理员组合.
-- =============================================================
