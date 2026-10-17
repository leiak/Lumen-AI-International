-- =============================================================
-- V26 — Iteration 4.3.1 PR1: lumen-finance 计费引擎 + 单据基础
-- =============================================================
-- 内容:
--   1. 6 张业务表 schema:
--        t_billing_rule / t_billing_rule_item / t_billing_event_log
--        t_billing_accrual / t_billing_invoice / t_billing_rule_audit
--   2. RBAC seed:
--        sys_role             +2 行 (id 11/12 finance_ops / finance_reviewer)
--        sys_permission       +6 行 (id 130..135 finance:*)
--        sys_role_permission  +8 行 (id 276..283)
--   3. t_state_transition seed:
--        billing_invoice  4 个 transition (DRAFT -> PENDING_L1 / REJECTED,
--                                        PENDING_L1 -> L1_APPROVED / REJECTED)
--        billing_accrual  4 个 transition (OPEN -> LOCKED/OPEN_FAILED/RELEASED,
--                                         LOCKED -> CONVERTED)
--   4. 当前 id 占用 (V25 末态):
--        sys_role             上限 10
--        sys_permission       上限 128
--        sys_role_permission  上限 275
--      本次 +6/+2/+8 后上限 = 135 / 12 / 283. 安全, 无冲突.
--
--   列约定沿用 V21/V25:
--     sys_permission (id, tenant_id, code, type, name, path, sort_order, status, version, deleted)
--     sys_role       (id, tenant_id, code, name, description, status, version, deleted)
--     sys_role_permission (id, role_id, permission_id, tenant_id, created_at)
--   状态机沿用 V8/V20/V22/V23:
--     t_state_transition (state_machine_code, from_state, to_state, transition_name,
--                          guard_expression, description, sort_order, tenant_id, ...)
--   真实角色通过 guard_expression 表达 (V20/V22/V23 同样模式).
--   注: StateMachineEngine 当前不解析 guard_expression, 留 documentary;
--       实际访问控制走 controller @PreAuthorize (V25 注释同).
-- =============================================================

-- ------------------------------ 1. 6 业务表 schema ------------------------------

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

-- ------------------------------ 2. RBAC seed ------------------------------

-- 6 个 finance:* 权限 (id 130..135)
-- path/sort_order 用于前端菜单; type='API' 与现有 @PreAuthorize hasAuthority 一致.
INSERT INTO sys_permission (id, tenant_id, code, type, name, path, sort_order, status, version, deleted)
VALUES
(130, 1, 'finance:rule:view',          'API', '查看计费规则',   '/finance/rules',              1001, 1, 0, 0),
(131, 1, 'finance:rule:edit',          'API', '编辑计费规则',   '/finance/rules/edit',         1002, 1, 0, 0),
(132, 1, 'finance:invoice:view',       'API', '查看应收应付',   '/finance/invoices',           1003, 1, 0, 0),
(133, 1, 'finance:invoice:submit',     'API', '提交单据初审',   '/finance/invoices/submit',    1004, 1, 0, 0),
(134, 1, 'finance:invoice:l1_approve', 'API', '财务初审审批',   '/finance/invoices/l1-approve',1005, 1, 0, 0),
(135, 1, 'finance:report:view',        'API', '查看报表',       '/finance/reports',            1006, 1, 0, 0)
ON DUPLICATE KEY UPDATE name = VALUES(name), path = VALUES(path), sort_order = VALUES(sort_order)
;

-- 2 个 finance_* 角色 (id 11/12)
INSERT INTO sys_role (id, tenant_id, code, name, description, status, version, deleted)
VALUES
(11, 1, 'finance_ops',      '财务运营', '计费规则配置 + 单据提交 + 报表查看', 1, 0, 0),
(12, 1, 'finance_reviewer', '财务初审', '单据初审 + 报表查看',                1, 0, 0)
ON DUPLICATE KEY UPDATE name = VALUES(name), description = VALUES(description)
;

-- 角色权限绑定 (id 276..283, 共 8 行)
-- finance_ops (11) -> rule:view/edit, invoice:view/submit, report:view = 5 行
-- finance_reviewer (12) -> invoice:view, invoice:l1_approve, report:view = 3 行
INSERT INTO sys_role_permission (id, role_id, permission_id, tenant_id, created_at) VALUES
(276, 11, 130, 1, NOW()),
(277, 11, 131, 1, NOW()),
(278, 11, 132, 1, NOW()),
(279, 11, 133, 1, NOW()),
(280, 11, 135, 1, NOW()),
(281, 12, 132, 1, NOW()),
(282, 12, 134, 1, NOW()),
(283, 12, 135, 1, NOW())
;

-- ------------------------------ 3. t_state_transition seed ------------------------------

-- billing_invoice 状态机 (4.3.1 仅 1 级审批; 4.3.2 PR2 加 PENDING_L2 / L2_APPROVED)
-- guard_expression 表达允许角色, 沿用 V20 'role:<code>' 字符串惯例.
INSERT INTO t_state_transition
    (state_machine_code, from_state, to_state, transition_name, guard_expression, description, sort_order, tenant_id, created_at, updated_at, deleted, version)
VALUES
('billing_invoice', 'DRAFT',      'PENDING_L1',  'submit',     'role:finance_ops',      '运营提交单据初审',   10, 0, NOW(), NOW(), 0, 1),
('billing_invoice', 'PENDING_L1', 'L1_APPROVED', 'l1_approve', 'role:finance_reviewer', '财务初审通过',       20, 0, NOW(), NOW(), 0, 1),
('billing_invoice', 'PENDING_L1', 'REJECTED',    'l1_reject',  'role:finance_reviewer', '财务初审驳回',       21, 0, NOW(), NOW(), 0, 1),
('billing_invoice', 'DRAFT',      'REJECTED',    'reject',     'role:finance_ops',      '运营自行撤回',       11, 0, NOW(), NOW(), 0, 1)
;

-- billing_accrual 状态机 (系统事件, guard 全部 'role:system' 沿用 V20/V22 惯例)
INSERT INTO t_state_transition
    (state_machine_code, from_state, to_state, transition_name, guard_expression, description, sort_order, tenant_id, created_at, updated_at, deleted, version)
VALUES
('billing_accrual', 'OPEN',   'LOCKED',     'finalize', 'role:system', '锁定待生成单据',   10, 0, NOW(), NOW(), 0, 1),
('billing_accrual', 'OPEN',   'OPEN_FAILED','fail',     'role:system', '计费失败回滚',     11, 0, NOW(), NOW(), 0, 1),
('billing_accrual', 'OPEN',   'RELEASED',   'release',  'role:system', '释放到下一周期',   12, 0, NOW(), NOW(), 0, 1),
('billing_accrual', 'LOCKED', 'CONVERTED',  'convert',  'role:system', '锁定后转为单据',   20, 0, NOW(), NOW(), 0, 1)
;

-- =============================================================
-- 备注:
--   - 本迁移改 5 张表:
--       t_billing_rule           +1 表
--       t_billing_rule_item      +1 表
--       t_billing_event_log      +1 表
--       t_billing_accrual        +1 表
--       t_billing_invoice        +1 表
--       t_billing_rule_audit     +1 表
--       sys_role                 +2 行 (id 11/12)
--       sys_permission           +6 行 (id 130..135)
--       sys_role_permission      +8 行 (id 276..283)
--       t_state_transition       +8 行 (billing_invoice 4 + billing_accrual 4)
--   - guard_expression 当前为 documentary, runtime 不解析 (同 V25 备注).
--     实际访问控制通过 controller @PreAuthorize('hasAuthority(''finance:*'')') 实现.
--   - t_billing_rule_item.expression / expression_hash 用于 Aviator 表达式 (lumen-finance pom
--     已引入 aviator 5.4.1); rule audit 表保存规则版本变更 diff.
--   - t_billing_invoice 唯一键: (tenant_id, invoice_no) + (tenant_id, source_accrual_id) —
--     单据号唯一 + 同一来源 accrual 只能生成一张单据.
-- =============================================================