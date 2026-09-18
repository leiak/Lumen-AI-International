-- ============================================================
-- V2__rbac.sql
-- Purpose:  RBAC 权限相关表（用户/角色/权限/关联/刷新令牌）+ 种子数据
-- Tables:   sys_user, sys_role, sys_permission, sys_user_role,
--           sys_role_permission, sys_refresh_token
-- Notes:    sys_user/role/permission 继承 BaseEntity 含 tenant_id;
--           sys_user_role / sys_role_permission / sys_refresh_token
--           为关联/会话表（带 tenant_id 但无 BaseEntity 全部审计列）。
--           种子数据：admin 用户 + SUPER_ADMIN 角色 + 绑定关系。
--           bcrypt 哈希由 Python `bcrypt` 库当场生成 (cost=10, 2b)，
--           对应明文密码 `admin123`（仅供开发环境；生产必须重置）。
-- ============================================================

-- ----------------------------------------------------------------
-- 用户表
-- 来源: lumen-rbac/src/main/java/com/lumen/rbac/entity/SysUser.java
-- ----------------------------------------------------------------
CREATE TABLE sys_user (
  id              BIGINT       NOT NULL COMMENT '主键',
  tenant_id       BIGINT       NOT NULL COMMENT '租户 ID',
  username        VARCHAR(64)  NOT NULL COMMENT '登录名（租户内唯一）',
  password_hash   VARCHAR(128) NOT NULL COMMENT 'BCrypt 密码哈希',
  real_name       VARCHAR(64)  DEFAULT NULL COMMENT '真实姓名',
  phone           VARCHAR(32)  DEFAULT NULL COMMENT '手机号',
  email           VARCHAR(128) DEFAULT NULL COMMENT '邮箱',
  status          INT          DEFAULT 1 COMMENT '状态: 1=启用 0=禁用',
  last_login_ip   VARCHAR(64)  DEFAULT NULL COMMENT '最近登录 IP',
  last_login_at   DATETIME     DEFAULT NULL COMMENT '最近登录时间',
  created_at      DATETIME     DEFAULT NULL COMMENT '创建时间',
  updated_at      DATETIME     DEFAULT NULL COMMENT '更新时间',
  deleted         INT          DEFAULT 0 COMMENT '逻辑删除: 0=正常 1=删除',
  version         INT          DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_tenant_username (tenant_id, username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户表';

CREATE INDEX idx_user_tenant_deleted ON sys_user(tenant_id, deleted);

-- ----------------------------------------------------------------
-- 角色表
-- 来源: lumen-rbac/src/main/java/com/lumen/rbac/entity/SysRole.java
-- 相比计划补全 description / data_scope 两列（实体有）。
-- ----------------------------------------------------------------
CREATE TABLE sys_role (
  id          BIGINT       NOT NULL COMMENT '主键',
  tenant_id   BIGINT       NOT NULL COMMENT '租户 ID',
  code        VARCHAR(64)  NOT NULL COMMENT '角色编码（租户内唯一）',
  name        VARCHAR(128) DEFAULT NULL COMMENT '角色名称',
  description VARCHAR(256) DEFAULT NULL COMMENT '描述',
  data_scope  VARCHAR(16)  DEFAULT NULL COMMENT '数据权限范围: ALL/DEPT/DEPT_AND_SUB/SELF/CUSTOM',
  status      INT          DEFAULT NULL COMMENT '状态: 1=启用 0=禁用',
  created_at  DATETIME     DEFAULT NULL COMMENT '创建时间',
  updated_at  DATETIME     DEFAULT NULL COMMENT '更新时间',
  deleted     INT          DEFAULT 0 COMMENT '逻辑删除: 0=正常 1=删除',
  version     INT          DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (id),
  UNIQUE KEY uk_role_tenant_code (tenant_id, code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='角色表';

CREATE INDEX idx_role_tenant_deleted ON sys_role(tenant_id, deleted);

-- ----------------------------------------------------------------
-- 权限表（菜单 / 按钮 / API）
-- 来源: lumen-rbac/src/main/java/com/lumen/rbac/entity/SysPermission.java
-- ----------------------------------------------------------------
CREATE TABLE sys_permission (
  id          BIGINT       NOT NULL COMMENT '主键',
  tenant_id   BIGINT       NOT NULL COMMENT '租户 ID',
  parent_id   BIGINT       DEFAULT NULL COMMENT '父权限 ID（用于树）',
  type        VARCHAR(16)  DEFAULT NULL COMMENT '类型: MENU/BUTTON/API',
  code        VARCHAR(128) DEFAULT NULL COMMENT '权限编码',
  name        VARCHAR(128) DEFAULT NULL COMMENT '权限名称',
  path        VARCHAR(256) DEFAULT NULL COMMENT '前端路由 path 或 API 路径',
  sort_order  INT          DEFAULT NULL COMMENT '排序',
  status      INT          DEFAULT NULL COMMENT '状态: 1=启用 0=禁用',
  created_at  DATETIME     DEFAULT NULL COMMENT '创建时间',
  updated_at  DATETIME     DEFAULT NULL COMMENT '更新时间',
  deleted     INT          DEFAULT 0 COMMENT '逻辑删除: 0=正常 1=删除',
  version     INT          DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (id),
  UNIQUE KEY uk_perm_tenant_code (tenant_id, code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='权限表';

CREATE INDEX idx_perm_tenant_deleted ON sys_permission(tenant_id, deleted);
CREATE INDEX idx_perm_parent         ON sys_permission(parent_id);

-- ----------------------------------------------------------------
-- 用户-角色关联
-- 来源: lumen-rbac/src/main/java/com/lumen/rbac/entity/SysUserRole.java
-- ----------------------------------------------------------------
CREATE TABLE sys_user_role (
  id          BIGINT   NOT NULL COMMENT '主键',
  user_id     BIGINT   NOT NULL COMMENT '用户 ID',
  role_id     BIGINT   NOT NULL COMMENT '角色 ID',
  tenant_id   BIGINT   NOT NULL COMMENT '租户 ID',
  created_at  DATETIME DEFAULT NULL COMMENT '创建时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_ur_tenant_user_role (tenant_id, user_id, role_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户角色关联';

CREATE INDEX idx_ur_user ON sys_user_role(user_id);
CREATE INDEX idx_ur_role ON sys_user_role(role_id);

-- ----------------------------------------------------------------
-- 角色-权限关联
-- 来源: lumen-rbac/src/main/java/com/lumen/rbac/entity/SysRolePermission.java
-- ----------------------------------------------------------------
CREATE TABLE sys_role_permission (
  id            BIGINT   NOT NULL COMMENT '主键',
  role_id       BIGINT   NOT NULL COMMENT '角色 ID',
  permission_id BIGINT   NOT NULL COMMENT '权限 ID',
  tenant_id     BIGINT   NOT NULL COMMENT '租户 ID',
  created_at    DATETIME DEFAULT NULL COMMENT '创建时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_rp_tenant_role_perm (tenant_id, role_id, permission_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='角色权限关联';

CREATE INDEX idx_rp_role ON sys_role_permission(role_id);
CREATE INDEX idx_rp_perm ON sys_role_permission(permission_id);

-- ----------------------------------------------------------------
-- 刷新令牌
-- 来源: lumen-rbac/src/main/java/com/lumen/rbac/entity/SysRefreshToken.java
-- ----------------------------------------------------------------
CREATE TABLE sys_refresh_token (
  id          BIGINT       NOT NULL COMMENT '主键',
  jti         VARCHAR(64)  NOT NULL COMMENT 'JWT ID（全局唯一）',
  user_id     BIGINT       NOT NULL COMMENT '用户 ID',
  tenant_id   BIGINT       NOT NULL COMMENT '租户 ID',
  expires_at  DATETIME     DEFAULT NULL COMMENT '过期时间',
  created_at  DATETIME     DEFAULT NULL COMMENT '签发时间',
  revoked     INT          DEFAULT 0 COMMENT '是否撤销: 0=有效 1=已撤销',
  PRIMARY KEY (id),
  UNIQUE KEY uk_refresh_jti (jti)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='刷新令牌';

CREATE INDEX idx_refresh_user ON sys_refresh_token(user_id);

-- ----------------------------------------------------------------
-- 种子数据：默认租户 (id=1) + 超级管理员
-- bcrypt 哈希 (cost=10, prefix=2b) 对应明文 admin123 —— 仅开发环境
-- ----------------------------------------------------------------
INSERT INTO sys_user (id, tenant_id, username, password_hash, real_name, status, version, deleted)
VALUES (1, 1, 'admin',
        '$2b$10$BTUt4e0NUV8piokyKgjRneCPeVD75wUiGlCawa65HYfrqLF/CFrVO',
        '超级管理员', 1, 0, 0);

INSERT INTO sys_role (id, tenant_id, code, name, status, version, deleted)
VALUES (1, 1, 'SUPER_ADMIN', '超级管理员', 1, 0, 0);

INSERT INTO sys_user_role (id, user_id, role_id, tenant_id)
VALUES (1, 1, 1, 1);

-- ----------------------------------------------------------------
-- 权限种子（SUPER_ADMIN 全量授予）
-- 权限编码必须与各控制器 @PreAuthorize 注解中的 hasAuthority(...) 字符串一致
-- ----------------------------------------------------------------
INSERT INTO sys_permission (id, tenant_id, code, type, name, status, version, deleted) VALUES
  (1,  1, 'user:list',                 'API', '用户列表', 1, 0, 0),
  (2,  1, 'user:view',                 'API', '用户查看', 1, 0, 0),
  (3,  1, 'user:create',               'API', '用户创建', 1, 0, 0),
  (4,  1, 'user:update',               'API', '用户更新', 1, 0, 0),
  (5,  1, 'user:delete',               'API', '用户删除', 1, 0, 0),
  (6,  1, 'user:assign-role',          'API', '用户分配角色', 1, 0, 0),
  (7,  1, 'user:reset-password',       'API', '用户重置密码', 1, 0, 0),
  (8,  1, 'role:list',                 'API', '角色列表', 1, 0, 0),
  (9,  1, 'role:view',                 'API', '角色查看', 1, 0, 0),
  (10, 1, 'role:create',               'API', '角色创建', 1, 0, 0),
  (11, 1, 'role:update',               'API', '角色更新', 1, 0, 0),
  (12, 1, 'role:delete',               'API', '角色删除', 1, 0, 0),
  (13, 1, 'role:assign-permission',    'API', '角色分配权限', 1, 0, 0),
  (14, 1, 'permission:list',           'API', '权限列表', 1, 0, 0),
  (15, 1, 'permission:matrix',         'API', '权限矩阵', 1, 0, 0),
  (16, 1, 'country:list',              'API', '国家列表', 1, 0, 0),
  (17, 1, 'country:view',              'API', '国家查看', 1, 0, 0),
  (18, 1, 'country:create',            'API', '国家创建', 1, 0, 0),
  (19, 1, 'country:update',            'API', '国家更新', 1, 0, 0),
  (20, 1, 'country:delete',            'API', '国家删除', 1, 0, 0),
  (21, 1, 'number-rule:list',          'API', '单号规则列表', 1, 0, 0),
  (22, 1, 'number-rule:view',          'API', '单号规则查看', 1, 0, 0),
  (23, 1, 'number-rule:create',        'API', '单号规则创建', 1, 0, 0),
  (24, 1, 'number-rule:update',        'API', '单号规则更新', 1, 0, 0),
  (25, 1, 'number-rule:delete',        'API', '单号规则删除', 1, 0, 0),
  (26, 1, 'number-rule:preview',       'API', '单号预览', 1, 0, 0),
  (27, 1, 'notification_template:send','API', '发送通知', 1, 0, 0);

-- SUPER_ADMIN 绑定全部权限 (1..27)
INSERT INTO sys_role_permission (id, role_id, permission_id, tenant_id)
SELECT seq, 1, seq, 1 FROM (
  SELECT a.N + b.N * 10 + 1 AS seq FROM
  (SELECT 0 AS N UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
   UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) a
  CROSS JOIN
  (SELECT 0 AS N UNION ALL SELECT 1 UNION ALL SELECT 2) b
  WHERE a.N + b.N * 10 + 1 <= 27
) seqs;