-- 审批子系统权限种子。
-- V6 已分配到 id=47（最高）；approval 权限从 id=48 起。
-- 挂到 admin 角色（role_id=1），让默认 admin 用户拿到审批权限。

INSERT INTO sys_permission (id, tenant_id, code, type, name, status, version, deleted, sort_order) VALUES
(48, 1, 'approval:list',    'PERMISSION', '审批-列表',  1, 1, 0, 100),
(49, 1, 'approval:approve', 'PERMISSION', '审批-决定',  1, 1, 0, 100);

INSERT INTO sys_role_permission (id, tenant_id, role_id, permission_id) VALUES
(48, 1, 1, 48),
(49, 1, 1, 49);
