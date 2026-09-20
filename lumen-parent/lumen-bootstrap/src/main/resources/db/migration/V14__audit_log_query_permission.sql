-- =============================================================
-- V14: 补齐 audit:list 权限种子（SysAuditLogController 需要）
-- =============================================================
-- 背景：V2 / V6 给 RBAC / 业务模块种了 47 个权限，但 audit log query 没有 controller
--      也就不需要权限。SysAuditLogController 上线需要 audit:list。
-- V13 用了 48（approval:list）+ 49（approval:approve），所以 audit:list 从 50 起。

INSERT INTO sys_permission (id, tenant_id, code, type, name, status, version, deleted)
VALUES (50, 1, 'audit:list', 'API', '审计日志-列表', 1, 0, 0)
;

-- 全部授权给 SUPER_ADMIN (role_id = 1)
INSERT INTO sys_role_permission (id, role_id, permission_id, tenant_id, created_at)
VALUES (50, 1, 50, 1, NOW())
;