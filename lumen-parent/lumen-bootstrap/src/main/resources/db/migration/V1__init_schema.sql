-- ============================================================
-- V1__init_schema.sql
-- Purpose:  平台基础日志表（审计日志 + 登录日志）
-- Tables:   sys_audit_log, sys_login_log
-- Notes:    两表均为租户级表（继承 BaseEntity 的 tenant_id 列），
--           列对齐 AuditLog（com.lumen.common.audit.AuditLog）
--           与 SysLoginLog（com.lumen.rbac.entity.SysLoginLog）。
--           不建立外键（按项目惯例，由应用层保证一致性）。
-- ============================================================

-- ----------------------------------------------------------------
-- 审计日志
-- 来源: lumen-common/src/main/java/com/lumen/common/audit/AuditLog.java
-- ----------------------------------------------------------------
CREATE TABLE sys_audit_log (
  id           BIGINT       NOT NULL COMMENT '主键',
  trace_id     VARCHAR(64)  DEFAULT NULL COMMENT '链路追踪 ID',
  user_id      BIGINT       DEFAULT NULL COMMENT '操作用户 ID',
  tenant_id    BIGINT       DEFAULT NULL COMMENT '租户 ID',
  username     VARCHAR(64)  DEFAULT NULL COMMENT '操作用户名（冗余）',
  action       VARCHAR(64)  DEFAULT NULL COMMENT '操作类型',
  resource     VARCHAR(64)  DEFAULT NULL COMMENT '资源类型',
  resource_id  VARCHAR(64)  DEFAULT NULL COMMENT '资源 ID',
  method       VARCHAR(8)   DEFAULT NULL COMMENT 'HTTP 方法',
  uri          VARCHAR(256) DEFAULT NULL COMMENT '请求 URI',
  request      TEXT         DEFAULT NULL COMMENT '请求体摘要',
  response     TEXT         DEFAULT NULL COMMENT '响应体摘要',
  status       INT          DEFAULT NULL COMMENT '状态: 1=成功 0=失败',
  cost_ms      BIGINT       DEFAULT NULL COMMENT '耗时(毫秒)',
  error_msg    VARCHAR(1024) DEFAULT NULL COMMENT '错误信息',
  created_at   DATETIME     DEFAULT NULL COMMENT '创建时间',
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='审计日志';

CREATE INDEX idx_audit_user   ON sys_audit_log(user_id, created_at);
CREATE INDEX idx_audit_tenant ON sys_audit_log(tenant_id, created_at);

-- ----------------------------------------------------------------
-- 登录日志
-- 来源: lumen-rbac/src/main/java/com/lumen/rbac/entity/SysLoginLog.java
-- ----------------------------------------------------------------
CREATE TABLE sys_login_log (
  id          BIGINT       NOT NULL COMMENT '主键',
  username    VARCHAR(64)  DEFAULT NULL COMMENT '登录用户名',
  user_id     BIGINT       DEFAULT NULL COMMENT '用户 ID',
  tenant_id   BIGINT       DEFAULT NULL COMMENT '租户 ID',
  ip          VARCHAR(64)  DEFAULT NULL COMMENT '客户端 IP',
  user_agent  VARCHAR(256) DEFAULT NULL COMMENT '浏览器 UA',
  status      INT          DEFAULT NULL COMMENT '状态: 1=成功 0=失败',
  error_msg   VARCHAR(512) DEFAULT NULL COMMENT '失败原因',
  created_at  DATETIME     DEFAULT NULL COMMENT '登录时间',
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='登录日志';

CREATE INDEX idx_loginlog_user      ON sys_login_log(username, created_at);
CREATE INDEX idx_loginlog_user_id   ON sys_login_log(user_id, created_at);
CREATE INDEX idx_loginlog_tenant    ON sys_login_log(tenant_id, created_at);