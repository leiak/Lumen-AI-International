-- =============================================================
-- lumen-finance test migration V1
-- =============================================================
-- 仅包含 BillingRuleMapperTest smoke test 真正读写的 6 张表 schema.
-- V26 完整 SQL（含 sys_role/sys_permission/sys_role_permission/t_state_transition
-- RBAC + state-transition INSERTs）由 lumen-bootstrap 主模块加载，本测试不依赖
-- RBAC 行存在 — Mapper smoke test 只验证 6 张业务表与 MyBatis-Plus 元数据对齐.
--
-- 摘自 lumen-bootstrap/src/main/resources/db/migration/V26__billing_schema_and_rbac.sql
-- 的 CREATE TABLE 段，省略 INSERT 段.
-- =============================================================

CREATE TABLE t_billing_rule (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  tenant_id BIGINT NOT NULL,
  business_type VARCHAR(32) NOT NULL,
  direction VARCHAR(16) NOT NULL,
  counterparty_type VARCHAR(16) NOT NULL,
  currency CHAR(3) NOT NULL DEFAULT 'CNY',
  priority INT NOT NULL DEFAULT 100,
  status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
  effective_from DATE NOT NULL,
  effective_to DATE,
  remark VARCHAR(255),
  created_by BIGINT,
  created_at DATETIME(3) NOT NULL,
  updated_by BIGINT,
  updated_at DATETIME(3) NOT NULL,
  deleted TINYINT NOT NULL DEFAULT 0,
  version INT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_rule (tenant_id, business_type, direction, counterparty_type, effective_from, deleted),
  KEY idx_status (tenant_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE t_billing_rule_item (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  rule_id BIGINT NOT NULL,
  sequence INT NOT NULL,
  item_type VARCHAR(16) NOT NULL,
  expression TEXT NOT NULL,
  expression_hash CHAR(64) NOT NULL,
  param_json JSON,
  result_field VARCHAR(32) NOT NULL DEFAULT 'gross_amount',
  remark VARCHAR(255),
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  deleted TINYINT NOT NULL DEFAULT 0,
  version INT NOT NULL DEFAULT 0,
  KEY idx_rule (rule_id, sequence, deleted),
  CONSTRAINT fk_ri_rule FOREIGN KEY (rule_id) REFERENCES t_billing_rule(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE t_billing_event_log (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  tenant_id BIGINT NOT NULL,
  source_event_id VARCHAR(64) NOT NULL,
  business_type VARCHAR(32) NOT NULL,
  business_id BIGINT NOT NULL,
  received_at DATETIME(3) NOT NULL,
  processed_at DATETIME(3),
  process_status VARCHAR(16) NOT NULL DEFAULT 'RECEIVED',
  error_msg VARCHAR(1000),
  deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_event (tenant_id, source_event_id, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE t_billing_accrual (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  tenant_id BIGINT NOT NULL,
  source_event_id VARCHAR(64) NOT NULL,
  business_type VARCHAR(32) NOT NULL,
  business_id BIGINT NOT NULL,
  rule_id BIGINT NOT NULL,
  direction VARCHAR(16) NOT NULL,
  counterparty_id BIGINT,
  gross_amount DECIMAL(18,2) NOT NULL,
  tax_amount DECIMAL(18,2) NOT NULL DEFAULT 0,
  net_amount DECIMAL(18,2) NOT NULL,
  currency CHAR(3) NOT NULL DEFAULT 'CNY',
  status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
  payload_json JSON,
  error_msg VARCHAR(1000),
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  locked_at DATETIME(3),
  deleted TINYINT NOT NULL DEFAULT 0,
  version INT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_accrual (tenant_id, source_event_id, business_type, deleted),
  KEY idx_status (tenant_id, status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE t_billing_invoice (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  invoice_no VARCHAR(32) NOT NULL,
  tenant_id BIGINT NOT NULL,
  direction VARCHAR(16) NOT NULL,
  counterparty_id BIGINT NOT NULL,
  counterparty_type VARCHAR(16) NOT NULL,
  business_type VARCHAR(32) NOT NULL,
  source_accrual_id BIGINT,
  period_year INT,
  period_month INT,
  gross_amount DECIMAL(18,2) NOT NULL,
  tax_amount DECIMAL(18,2) NOT NULL DEFAULT 0,
  net_amount DECIMAL(18,2) NOT NULL,
  currency CHAR(3) NOT NULL DEFAULT 'CNY',
  status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
  rule_id BIGINT NOT NULL,
  rule_version INT NOT NULL,
  l1_approver_id BIGINT,
  l1_approved_at DATETIME(3),
  l1_comment VARCHAR(500),
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  submitted_at DATETIME(3),
  deleted TINYINT NOT NULL DEFAULT 0,
  version INT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_invoice_no (tenant_id, invoice_no, deleted),
  UNIQUE KEY uk_source_accrual (tenant_id, source_accrual_id, deleted),
  KEY idx_status (tenant_id, status, created_at),
  KEY idx_counterparty (tenant_id, counterparty_id, direction, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE t_billing_rule_audit (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  tenant_id BIGINT NOT NULL,
  rule_id BIGINT NOT NULL,
  rule_version INT NOT NULL,
  change_type VARCHAR(16) NOT NULL,
  diff_json JSON NOT NULL,
  operator_id BIGINT NOT NULL,
  changed_at DATETIME(3) NOT NULL,
  remark VARCHAR(255),
  deleted TINYINT NOT NULL DEFAULT 0,
  KEY idx_rule (rule_id, changed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;