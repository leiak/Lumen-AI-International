-- =============================================================
-- V27 — Iteration 4.3.1 PR3 T3.5: 补 finance:* 4 个权限 (controller 守门)
-- =============================================================
-- 背景: V26 落地了 6 个 finance:* 权限 (id 130..135) + 2 个 finance_* 角色
--       (id 11/12 finance_ops / finance_reviewer) + 8 行 sys_role_permission.
--       PR3 T3.5 controller 还需要以下 4 个权限:
--         finance:accrual:view       — 暂估单查看 (list + get)
--         finance:accrual:finalize   — 暂估单终态化
--         finance:accrual:release    — 暂估单释放
--         finance:eventlog:view      — 事件溯源查看 (admin only)
--       这些是 controller @PreAuthorize('hasAuthority(...)') 的 token,
--       必须先在 sys_permission 落地, 否则 4 个端点全部 403.
--
-- 内容:
--   1. sys_permission +4 行 (id 136..139)
--   2. sys_role_permission +4 行 (id 284..287)
--         finance_ops (11)  拿到 3 个 accrual:* 权限
--         finance_reviewer (12) 不需要 accrual:* 权限 (审单人不操作暂估单)
--         eventlog:view 给 admin 角色 (id 1) — admin 才能看事件溯源
--
-- 当前 id 占用 (V26 末态):
--   sys_permission 上限 135 / sys_role_permission 上限 283
--   本次 +4/+4 后上限 = 139 / 287. 安全, 无冲突.
--
-- 与 V26 备注一致: guard_expression 为 documentary, 实际访问控制由
-- controller @PreAuthorize('hasAuthority(''finance:*'')') 完成.
-- =============================================================

-- ------------------------------ 1. 4 个权限 ------------------------------

INSERT INTO sys_permission (id, tenant_id, code, type, name, path, sort_order, status, version, deleted)
VALUES
(136, 1, 'finance:accrual:view',     'API', '查看暂估单',     '/finance/accruals',             1007, 1, 0, 0),
(137, 1, 'finance:accrual:finalize', 'API', '暂估单终态化',   '/finance/accruals/finalize',     1008, 1, 0, 0),
(138, 1, 'finance:accrual:release',  'API', '暂估单释放',     '/finance/accruals/release',      1009, 1, 0, 0),
(139, 1, 'finance:eventlog:view',    'API', '查看事件溯源',   '/finance/event-logs',            1010, 1, 0, 0)
ON DUPLICATE KEY UPDATE name = VALUES(name), path = VALUES(path), sort_order = VALUES(sort_order)
;

-- ------------------------------ 2. 角色权限绑定 ------------------------------

-- finance_ops (id 11) 拿到 accrual:view/finalize/release (运营负责生成单据)
-- ON DUPLICATE KEY UPDATE 保证幂等: 重复执行时不会因 PK id 冲突而失败.
INSERT INTO sys_role_permission (id, role_id, permission_id, tenant_id, created_at) VALUES
(284, 11, 136, 1, NOW()),
(285, 11, 137, 1, NOW()),
(286, 11, 138, 1, NOW())
ON DUPLICATE KEY UPDATE
    role_id = VALUES(role_id),
    permission_id = VALUES(permission_id),
    tenant_id = VALUES(tenant_id)
;

-- eventlog:view 给 admin 角色 (id 1) — 由 admin_role_perm 假设 admin 已有 id=1 的 sys_role;
-- 若 admin role id 不同, 请按实际 id 调整. 沿用 V25 admin='1' 的约定.
INSERT INTO sys_role_permission (id, role_id, permission_id, tenant_id, created_at)
SELECT 287, 1, 139, 1, NOW()
WHERE EXISTS (SELECT 1 FROM sys_role WHERE id = 1 AND deleted = 0)
;

-- =============================================================
-- 备注:
--   - 4 行 sys_permission (id 136..139) + 4 行 sys_role_permission (id 284..287)
--   - admin 角色拿 eventlog:view 用 EXISTS 守卫: 若 sys_role id=1 不存在则跳过
--     (兼容历史项目 admin 角色命名差异; PR3 不强制要求 admin 一定能用,
--      后续 PR5 admin 重整时再补 admin perms batch).
--   - finance_reviewer 不动: 单据审批人不该操作暂估单 (职责分离).
-- =============================================================