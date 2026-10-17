-- =============================================================
-- V31 — Iteration 4.4 PR1: 支付记录 + 月结 + 对账 + 报表权限
-- =============================================================
-- 背景: 4.4 在 4.3.2 (V29/V30) 基础上:
--   - 加 3 张表: t_payment_record (实收/实付) / t_monthly_closing (月结) /
--     t_reconciliation (对账差异)
--   - 加 1 个状态机 monthly_closing (OPEN -> IN_PROGRESS -> CLOSED, 含 reopen)
--   - 加 4 个权限 (id 150..153) + 6 个 sys_role_permission (V30 末 306 -> 312)
--
-- 当前 id 占用 (V30 末态):
--   sys_permission        上限 149
--   sys_role              上限  13
--   sys_role_permission   上限 306
--   t_state_transition    独立 id 列
-- 本次 +0/+0/+6/+3 后, 安全, 无冲突.
-- =============================================================

-- ------------------------------ 1. t_payment_record ------------------------------

CREATE TABLE t_payment_record (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id BIGINT NOT NULL,
  period CHAR(7) NOT NULL,
  direction VARCHAR(20) NOT NULL,
  counterparty_id BIGINT NOT NULL,
  counterparty_type VARCHAR(20) NOT NULL,
  currency CHAR(3) NOT NULL,
  amount DECIMAL(18,4) NOT NULL,
  source_ref VARCHAR(100) NULL,
  payment_date DATE NOT NULL,
  source VARCHAR(20) NOT NULL,
  remark VARCHAR(500) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  created_by BIGINT NOT NULL,
  deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_payment_tenant_period_cpty (tenant_id, period, direction, counterparty_id, payment_date, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
;

-- ------------------------------ 2. t_monthly_closing ------------------------------

CREATE TABLE t_monthly_closing (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id BIGINT NOT NULL,
  period CHAR(7) NOT NULL,
  status VARCHAR(20) NOT NULL,
  snapshot_total_invoice DECIMAL(20,4) NULL,
  snapshot_total_accrual DECIMAL(20,4) NULL,
  snapshot_total_adjustment DECIMAL(20,4) NULL,
  snapshot_currency CHAR(3) NULL,
  snapshot_recalculated_at DATETIME(3) NULL,
  closed_by BIGINT NULL,
  closed_at DATETIME(3) NULL,
  reopen_reason VARCHAR(500) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_closing_tenant_period (tenant_id, period, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
;

-- ------------------------------ 3. t_reconciliation ------------------------------

CREATE TABLE t_reconciliation (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id BIGINT NOT NULL,
  period CHAR(7) NOT NULL,
  direction VARCHAR(20) NOT NULL,
  counterparty_id BIGINT NOT NULL,
  counterparty_type VARCHAR(20) NOT NULL,
  currency CHAR(3) NOT NULL,
  expected_amount DECIMAL(18,4) NOT NULL,
  actual_amount DECIMAL(18,4) NOT NULL,
  variance_amount DECIMAL(18,4) NOT NULL,
  adjustment_id BIGINT NULL,
  reconciliation_status VARCHAR(20) NOT NULL,
  remark VARCHAR(500) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_recon_tenant_period_cpty (tenant_id, period, direction, counterparty_id, currency, deleted),
  KEY idx_recon_adjustment (adjustment_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
;

-- ------------------------------ 4. monthly_closing 状态机 ------------------------------

INSERT INTO t_state_transition
    (state_machine_code, from_state, to_state, transition_name, guard_expression, description, sort_order, tenant_id, created_at, updated_at, deleted, version)
VALUES
('monthly_closing', 'OPEN',        'IN_PROGRESS', 'start',  'role:finance_manager',                  '财务经理开始月结',       10, 0, NOW(), NOW(), 0, 1),
('monthly_closing', 'IN_PROGRESS', 'CLOSED',      'close',  'role:finance_manager',                  '财务经理完成月结',       20, 0, NOW(), NOW(), 0, 1),
('monthly_closing', 'CLOSED',      'IN_PROGRESS', 'reopen', 'role:finance_manager &amp;&amp; reopen_reason!=null', '月结重开 (有原因)', 30, 0, NOW(), NOW(), 0, 1)
;

-- ------------------------------ 5. RBAC: 4 权限 (id 150..153) ------------------------------

INSERT INTO sys_permission (id, tenant_id, parent_id, code, type, name, path, sort_order, status, version, deleted, created_at, updated_at)
VALUES
(150, 1, NULL, 'finance:payment:view',   'API', '实收/实付查看', '/finance/payments',  1021, 1, 0, 0, NOW(), NOW()),
(151, 1, NULL, 'finance:payment:edit',   'API', '实收/实付录入', '/finance/payments',  1022, 1, 0, 0, NOW(), NOW()),
(152, 1, NULL, 'finance:closing:manage', 'API', '月结管理',     '/finance/closing',   1023, 1, 0, 0, NOW(), NOW()),
(153, 1, NULL, 'finance:report:export',  'API', '报表导出',     '/finance/reports',   1024, 1, 0, 0, NOW(), NOW())
ON DUPLICATE KEY UPDATE name = VALUES(name), path = VALUES(path), sort_order = VALUES(sort_order)
;

-- ------------------------------ 6. RBAC: 6 sys_role_permission (id 307..312) ------------------------------

-- finance_manager (id 13) 绑 4 个
-- finance_ops (id 11) 绑 1 个
-- finance_reviewer (id 12) 绑 1 个
INSERT INTO sys_role_permission (id, role_id, permission_id, tenant_id, created_at) VALUES
(307, 13, 150, 1, NOW()),
(308, 13, 151, 1, NOW()),
(309, 13, 152, 1, NOW()),
(310, 13, 153, 1, NOW()),
(311, 11, 150, 1, NOW()),
(312, 12, 150, 1, NOW())
ON DUPLICATE KEY UPDATE role_id = VALUES(role_id), permission_id = VALUES(permission_id), tenant_id = VALUES(tenant_id)
;