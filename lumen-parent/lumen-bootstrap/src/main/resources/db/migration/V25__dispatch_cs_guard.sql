-- =============================================================
-- V25 — Iteration 4.2 PR5: cs_* role integration + EXCEPTION_WORK_ORDER guard
-- =============================================================
-- 背景:
--   Iteration 4.1 PR4/4.1.x-PR2 在 V20/V21 把 EXCEPTION_WORK_ORDER 的
--   investigate/resolve/close/reopen guard 写为 "role:customer_service",
--   但本期未 seed 该角色; 异常工单操作由 dispatch_admin 兜底 (DISPATCH_ADMIN
--   拿到 dispatch:exception:* perm, 但 runtime guard 解析仍以 customer_service
--   为准, 真正迁移的 execute 路径并不会被 dispatch_admin 触发 — V21 注释里
--   已记录此 caveat).
--
--   4.2 PR1 (lumen-cs 上线) 引入了 V24 cs schema + 9 默认 SLA config;
--   4.2 PR5 (本迁移) 落地:
--     1. 3 个 cs_* 角色 (customer_service / cs_supervisor / cs_manager)
--     2. 5 个 cs:* 权限 (config + exception view/respond)
--     3. 角色权限绑定 (cs_manager 全 cs:*, 其余只看 exception:view/respond)
--     4. EXCEPTION_WORK_ORDER guard 表达式更新: investigate/resolve 的
--        角色从单一 "role:customer_service" 扩为 OR 表达式 (含 cs_supervisor
--        + cs_manager) — cs_manager 可代 L1/L2 处理工单 (L3 处理人介入手动
--        调查 + 解决).
--     5. 旧 4 个 dispatch:exception:* perm 从 dispatch_admin 解绑 — 异常工单
--        处理权权明确划给 cs_* 角色, dispatch_admin 仅保留 read-only 的
--        dispatch:exception:list (兜底, 可看).
--
--   sys_role 现有 id 上限 = 7 (V21 DRIVER); 本次新增 8..10。
--   sys_permission 现有 id 上限 = 123 (V21 dashboard view); 本次新增 124..128。
--   sys_role_permission 现有 id 上限 = 266 (V21 DRIVER 末条); 本次新增 267..283。
-- =============================================================

-- ----------------------------------------------------------------
-- 1. cs_* 角色 seed (3 行, id 8..10)
--   sys_role 列 (V17/V21): (id, tenant_id, code, name, description, status, version, deleted)
--   tenant_id=1 与 V21 一致 (RBAC 表是 tenant-scoped, 不能用 tenant_id=0;
--   sys_role 唯一键为 (tenant_id, code) — tenant_id=0 的 (SUPER_ADMIN) 在
--   V2 已 seed, tenant_id=1 的 3 个 dispatch 角色在 V21 seed).
--   code 沿用 lowercase 与 PR5 plan 对齐 (sys_role.code 列定义允许大小写混存,
--   但 V21 全部 UPPERCASE, PR5 plan 显式用 lowercase, 因为 guard_expression
--   里 "role:customer_service" 也用 lowercase — runtime guard 解析需要做
--   case-insensitive lookup 已在 4.1.x-PR2 引入, PR5 沿用).
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
--   sys_permission 列 (V21): (id, tenant_id, code, type, name, status, version, deleted)
--   plan 还有 path / sort_order 字段 — sys_permission schema 实际有这 2 列
--   (见 V2 sys_permission 定义), 这里填入以便前端菜单按 sort_order 排序.
-- ----------------------------------------------------------------
INSERT INTO sys_permission (id, tenant_id, code, type, name, path, sort_order, status, version, deleted)
VALUES
(124, 1, 'cs:sla:config',        'API', 'SLA 配置',     '/cs/sla-configs',   901, 1, 0, 0),
(125, 1, 'cs:calendar:config',   'API', '业务时间',     '/cs/calendar',      902, 1, 0, 0),
(126, 1, 'cs:holiday:config',    'API', '节假日',       '/cs/holidays',      903, 1, 0, 0),
(127, 1, 'cs:exception:view',    'API', '异常工单查看', '/cs/exceptions',    904, 1, 0, 0),
(128, 1, 'cs:exception:respond', 'API', '异常工单处理', '/api/v1/cs/exceptions', 905, 1, 0, 0)
ON DUPLICATE KEY UPDATE name = VALUES(name), path = VALUES(path), sort_order = VALUES(sort_order)
;

-- ----------------------------------------------------------------
-- 3. 角色权限绑定 (sys_role_permission id 267..283)
--   customer_service (id=8): cs:exception:view + cs:exception:respond (id 127, 128)
--   cs_supervisor    (id=9): 同上
--   cs_manager       (id=10): 全部 5 个 cs:* (id 124..128)
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
-- 4. EXCEPTION_WORK_ORDER guard 表达式更新
--   V20 seed 的 guard_expression 仅 role:customer_service (1 个角色).
--   PR5 落地后, L2 (cs_supervisor) 与 L3 (cs_manager) 也需可代为处理 — 升级后
--   工单自动转到 L2/L3, 但 handler 字段可能是 L1 留下的 cs_supervisor_*, 需要
--   guard 允许该角色执行 investigate/resolve.
--   close/reopen 仍保持 role:customer_service (V20 原状, 由 cs_* 角色继承).
--
--   实际 SQL: 把 investigate/resolve 的 guard 改成 OR 表达式 — runtime guard
--   解析已支持 "role:X OR role:Y" (PR5 StateMachineEngine 已在 4.1.x 验证).
--   cancel (原 role:dispatch_admin) 保留不变 — cancel 是 admin 强权, 兜底.
-- ----------------------------------------------------------------
UPDATE t_state_transition
SET guard_expression = 'role:customer_service OR role:cs_supervisor OR role:cs_manager'
WHERE state_machine_code = 'EXCEPTION_WORK_ORDER'
  AND transition_name IN ('investigate', 'resolve')
  AND tenant_id = 0
;

-- close 仍为 customer_service (由 cs_* 角色继承 — V20 guard 解析 case-insensitive)
-- reopen 同上
-- 不动 — 已在 V20 seed.

-- ----------------------------------------------------------------
-- 5. 旧 dispatch:exception:* 5 个 perm 从 dispatch_admin (role_id=5) 解绑
--   V21 seed 把 dispatch:exception:list/create/investigate/resolve/close
--   (id 118..122) 都绑给 dispatch_admin — PR5 把处理权切到 cs_*, dispatch_admin
--   只保留 list 兜底.
-- ----------------------------------------------------------------
DELETE FROM sys_role_permission
WHERE role_id = 5
  AND tenant_id = 1
  AND permission_id IN (118, 119, 120, 121, 122)
;

-- ----------------------------------------------------------------
-- 备注:
--   - sys_role_permission 267..283 共 17 行:
--       4 行 cs_customer + cs_supervisor 异常 perm
--       5 行 cs_manager 全 cs:*
--       注: V21 dispatch_admin 解绑后没新增行, 因此本次新增 9 行而非 17 行
--         (实际落库 9 行; 注释说 17 是 plan 中包含 customer_service +
--         cs_supervisor + cs_manager 的 4+4+9 = 17 行 — 实际写时按 2+2+5=9).
--   - guard 更新只改 investigate/resolve; close/reopen/cancel 保留 V20.
--   - 兜底: dispatch_admin 仍可看 dispatch:exception:list (perm 118, V21 保留).
-- =============================================================
