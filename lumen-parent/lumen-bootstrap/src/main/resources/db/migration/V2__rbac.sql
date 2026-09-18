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