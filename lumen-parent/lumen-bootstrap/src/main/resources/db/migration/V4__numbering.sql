-- ============================================================
-- V4__numbering.sql
-- Purpose:  单号规则与序列计数器表
-- Tables:   sys_number_rule, sys_number_sequence
-- Notes:    sys_number_sequence 是无租户维度的全局计数器，
--           实体不继承 BaseEntity，仅含 id/ruleCode/period/
--           currentValue/version/updatedAt。
-- ============================================================

-- ----------------------------------------------------------------
-- 编号规则
-- 来源: lumen-numbering/src/main/java/com/lumen/numbering/entity/SysNumberRule.java
-- ----------------------------------------------------------------
CREATE TABLE sys_number_rule (
  id            BIGINT       NOT NULL COMMENT '主键',
  tenant_id     BIGINT       DEFAULT NULL COMMENT '租户 ID',
  code          VARCHAR(64)  DEFAULT NULL COMMENT '规则编码',
  prefix        VARCHAR(32)  DEFAULT NULL COMMENT '单号前缀',
  date_format   VARCHAR(16)  DEFAULT NULL COMMENT '日期段格式 (yyyyMMdd)',
  seq_length    INT          DEFAULT NULL COMMENT '序号位数',
  reset_policy  VARCHAR(16)  DEFAULT NULL COMMENT '重置策略: NONE/DAY/MONTH/YEAR',
  current_value BIGINT       DEFAULT NULL COMMENT '当前序号值',
  description   VARCHAR(256) DEFAULT NULL COMMENT '描述',
  created_at    DATETIME     DEFAULT NULL COMMENT '创建时间',
  updated_at    DATETIME     DEFAULT NULL COMMENT '更新时间',
  deleted       INT          DEFAULT 0 COMMENT '逻辑删除: 0=正常 1=删除',
  version       INT          DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (id),
  UNIQUE KEY uk_number_rule_tenant_code (tenant_id, code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='单号规则';

CREATE INDEX idx_number_rule_tenant_deleted ON sys_number_rule(tenant_id, deleted);

-- ----------------------------------------------------------------
-- 序号计数器（无租户维度；按 rule_code + period 唯一）
-- 来源: lumen-numbering/src/main/java/com/lumen/numbering/entity/SysNumberSequence.java
-- ----------------------------------------------------------------
CREATE TABLE sys_number_sequence (
  id            BIGINT   NOT NULL COMMENT '主键',
  rule_code     VARCHAR(64)  DEFAULT NULL COMMENT '规则编码',
  period        VARCHAR(16)  DEFAULT NULL COMMENT '周期 (例如 20260918)',
  current_value BIGINT   DEFAULT NULL COMMENT '当前值',
  version       INT      DEFAULT NULL COMMENT '乐观锁版本',
  updated_at    DATETIME DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_seq_rule_period (rule_code, period)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='序号计数器';

-- ----------------------------------------------------------------
-- 种子：默认 ORDER 规则（每日重置、6 位序号）
-- ----------------------------------------------------------------
INSERT INTO sys_number_rule (id, tenant_id, code, prefix, date_format, seq_length, reset_policy, current_value)
VALUES (1, 1, 'ORDER', 'ORD', 'yyyyMMdd', 6, 'DAY', 0);