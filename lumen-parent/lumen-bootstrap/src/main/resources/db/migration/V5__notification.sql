-- ============================================================
-- V5__notification.sql
-- Purpose:  通知模板 + 发送日志
-- Tables:   notification_template, notification_send_log
-- Notes:    notification_template 继承 BaseEntity，仍受 MP 多租户拦截器过滤；
--           notification_send_log 为 standalone 表（仅冗余 tenant_id，
--           无 deleted/version/updated_at —— 对齐实体）。
--           ** notification_send_log 已显式加入 MybatisPlusConfig.ignoreTable()
--              的豁免名单**，以便运维/审计场景进行跨租户检索；
--           tenant_id 列仅用于分片与统计冗余，不再承担数据隔离职责。
-- ============================================================

-- ----------------------------------------------------------------
-- 通知模板
-- 来源: lumen-notification/src/main/java/com/lumen/notification/entity/NotificationTemplate.java
-- ----------------------------------------------------------------
CREATE TABLE notification_template (
  id          BIGINT       NOT NULL COMMENT '主键',
  tenant_id   BIGINT       DEFAULT NULL COMMENT '租户 ID',
  code        VARCHAR(64)  DEFAULT NULL COMMENT '模板编码（租户内唯一）',
  channel     VARCHAR(16)  DEFAULT NULL COMMENT '渠道: EMAIL/SMS/IN_APP',
  subject     VARCHAR(256) DEFAULT NULL COMMENT '主题/标题',
  content     TEXT         DEFAULT NULL COMMENT '正文（支持 ${var} 占位）',
  vars        VARCHAR(1024) DEFAULT NULL COMMENT '变量清单（JSON 或 CSV）',
  status      INT          DEFAULT NULL COMMENT '状态: 1=启用 0=禁用',
  created_at  DATETIME     DEFAULT NULL COMMENT '创建时间',
  updated_at  DATETIME     DEFAULT NULL COMMENT '更新时间',
  deleted     INT          DEFAULT 0 COMMENT '逻辑删除: 0=正常 1=删除',
  version     INT          DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (id),
  UNIQUE KEY uk_ntpl_tenant_code (tenant_id, code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='通知模板';

CREATE INDEX idx_ntpl_tenant_deleted ON notification_template(tenant_id, deleted);
CREATE INDEX idx_ntpl_channel         ON notification_template(channel);

-- ----------------------------------------------------------------
-- 发送日志（standalone；冗余 tenant_id 仅用于分片/统计）
-- 来源: lumen-notification/src/main/java/com/lumen/notification/entity/NotificationSendLog.java
-- ----------------------------------------------------------------
CREATE TABLE notification_send_log (
  id             BIGINT       NOT NULL COMMENT '主键',
  template_code  VARCHAR(64)  DEFAULT NULL COMMENT '模板编码',
  channel        VARCHAR(16)  DEFAULT NULL COMMENT '渠道',
  receiver       VARCHAR(256) DEFAULT NULL COMMENT '接收方（邮箱/手机号/用户 ID）',
  payload        TEXT         DEFAULT NULL COMMENT '实际发送载荷',
  status         INT          DEFAULT NULL COMMENT '状态: 1=成功 0=失败',
  error_msg      VARCHAR(1024) DEFAULT NULL COMMENT '错误信息',
  tenant_id      BIGINT       DEFAULT NULL COMMENT '租户 ID（冗余）',
  created_at     DATETIME     DEFAULT NULL COMMENT '发送时间',
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='通知发送日志';

CREATE INDEX idx_nsl_template  ON notification_send_log(template_code);
CREATE INDEX idx_nsl_receiver  ON notification_send_log(receiver);
CREATE INDEX idx_nsl_tenant    ON notification_send_log(tenant_id, created_at);

-- ----------------------------------------------------------------
-- 种子：默认 WELCOME 模板（EMAIL）
-- ----------------------------------------------------------------
INSERT INTO notification_template (id, tenant_id, code, channel, subject, content, status)
VALUES (1, 1, 'WELCOME', 'EMAIL', '欢迎', '欢迎 ${name} 加入', 1);