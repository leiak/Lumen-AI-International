-- =============================================================
-- V32 — Iteration 4.4 PR2 RBAC gap: 对账细粒度权限 + 报表 view 权限
-- =============================================================
-- 背景: 4.4 PR2 控制器在 @PreAuthorize 上使用了更细粒度的权限码,
-- 但 V31 (commit 6dac7ce) seed 的权限码与之不匹配, 导致 PR2 的 7 个
-- 端点 (4 Reconciliation + 3 Report) 在生产环境 100% 被 @PreAuthorize
-- 拦截, 所有角色均无法访问:
--
--   BillingReconciliationController:
--     - POST /run           -> finance:reconciliation:run      (V31 未 seed)
--     - GET  /list          -> finance:reconciliation:view     (V31 未 seed)
--     - POST /{id}/resolve  -> finance:reconciliation:resolve  (V31 未 seed)
--     - POST /{id}/waive    -> finance:reconciliation:waive    (V31 未 seed)
--
--   BillingReportController:
--     - GET /summary        -> finance:report:view              (V31 seed 为 :export)
--     - GET /detail         -> finance:report:view              (同上)
--     - GET /variance       -> finance:report:view              (同上)
--
-- 本迁移补齐 4.4 PR2 实际需要的 5 个权限 (id 154..158) + 8 个
-- sys_role_permission (id 313..320).
--
-- 关于 finance:report:export (id 153) 与 finance:report:view (id 158):
--   - V31 seed 的 153 (:export) 保留不动, 仍绑定 finance_manager,
--     作为未来 "显式下载 XLSX" 动作的预留权限.
--   - 本次新增 158 (:view), 用于当前 BillingReportController 实际使用.
--   - 两个权限并存, 互不冲突.
--
-- 当前 id 占用 (V31 末态):
--   sys_permission        上限 153
--   sys_role              上限  13
--   sys_role_permission   上限 312
-- 本次 +5/+0/+8 后, 安全, 无冲突.
-- =============================================================

-- ------------------------------ 1. RBAC: 5 权限 (id 154..158) ------------------------------

INSERT INTO sys_permission (id, tenant_id, parent_id, code, type, name, path, sort_order, status, version, deleted, created_at, updated_at)
VALUES
(154, 1, NULL, 'finance:reconciliation:view',    'API', '对账差异查看',   '/finance/reconciliation',  1022, 1, 0, 0, NOW(), NOW()),
(155, 1, NULL, 'finance:reconciliation:run',     'API', '对账执行',      '/finance/reconciliation',  1023, 1, 0, 0, NOW(), NOW()),
(156, 1, NULL, 'finance:reconciliation:resolve', 'API', '对账标记已解决', '/finance/reconciliation',  1024, 1, 0, 0, NOW(), NOW()),
(157, 1, NULL, 'finance:reconciliation:waive',   'API', '对账豁免',      '/finance/reconciliation',  1025, 1, 0, 0, NOW(), NOW()),
(158, 1, NULL, 'finance:report:view',            'API', '财务报表查看',   '/finance/reports',         1026, 1, 0, 0, NOW(), NOW())
ON DUPLICATE KEY UPDATE
    name = VALUES(name),
    path = VALUES(path),
    sort_order = VALUES(sort_order)
;

-- ------------------------------ 2. RBAC: 8 sys_role_permission (id 313..320) ------------------------------

-- 角色分配策略 (沿用 V30/V31 模式, 与 4.3.x/4.4 已存在的角色粒度对齐):
--   finance:reconciliation:view  (154) -> finance_manager(13) + finance_reviewer(12) + finance_ops(11)
--                                     [广域可见, 与 V31 finance:payment:view 模式一致]
--   finance:reconciliation:run   (155) -> finance_manager(13) only
--                                     [高影响动作: 触发对账 + 自动调账单]
--   finance:reconciliation:resolve (156) -> finance_manager(13) only
--                                     [高影响动作: 标记对账已解决]
--   finance:reconciliation:waive (157) -> finance_manager(13) only
--                                     [高风险动作: 豁免差异, 绕过 variance]
--   finance:report:view          (158) -> finance_manager(13) + finance_reviewer(12)
--                                     [只读权限, 与 finance:payment:view 模式一致]

INSERT INTO sys_role_permission (id, role_id, permission_id, tenant_id, created_at) VALUES
(313, 13, 154, 1, NOW()),  -- finance_manager  : reconciliation:view
(314, 12, 154, 1, NOW()),  -- finance_reviewer : reconciliation:view
(315, 11, 154, 1, NOW()),  -- finance_ops      : reconciliation:view
(316, 13, 155, 1, NOW()),  -- finance_manager  : reconciliation:run
(317, 13, 156, 1, NOW()),  -- finance_manager  : reconciliation:resolve
(318, 13, 157, 1, NOW()),  -- finance_manager  : reconciliation:waive
(319, 13, 158, 1, NOW()),  -- finance_manager  : report:view
(320, 12, 158, 1, NOW())   -- finance_reviewer : report:view
ON DUPLICATE KEY UPDATE
    role_id = VALUES(role_id),
    permission_id = VALUES(permission_id),
    tenant_id = VALUES(tenant_id)
;

-- =============================================================
-- 备注:
--   - 本迁移只补 RBAC, 不动任何业务表结构.
--   - 修改的表:
--       sys_permission             +5 行 (id 154..158)
--       sys_role_permission        +8 行 (id 313..320)
--   - 对账 4 个权限 (154..157) 中 view 广域, run/resolve/waive 仅
--     finance_manager, 与 BillingReconciliationController @PreAuthorize
--     实际要求完全对齐, 修复 4 个 100% 不可达端点.
--   - 报表 1 个新权限 (158 finance:report:view) 与原 V31 seed 的 153
--     finance:report:export 并存: 153 是未来显式下载动作预留, 158 是
--     当前 BillingReportController 实际使用的 GET 权限; 与 V31 已
--     绑定 finance_manager(13, sys_role_permission 312) 的 153 不冲突.
--   - 所有 INSERT 都带 ON DUPLICATE KEY UPDATE, 重放安全.
-- =============================================================