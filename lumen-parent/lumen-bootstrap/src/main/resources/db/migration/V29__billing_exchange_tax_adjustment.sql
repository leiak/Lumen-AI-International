-- =============================================================
-- V29 — Iteration 4.3.2 PR1: 多币种 + 调账 + 全局税率
-- =============================================================
-- 背景: 4.3.2 引入 lumen-finance 多币种 (汇率历史)、全局税率 (不同币种增值税率不同)、
--       调账单 (L1 + L2 两级审批 + 多维度金额字段 + 原因)。三张表是后续 PR 的承载层。
--
-- 表设计:
--   t_exchange_rate   — append-only 汇率历史 (from/to/date/source 唯一)
--   t_tax_rate        — 全局税率 (currency, effective_from 索引)
--   t_billing_adjustment — 调账单 (tenant + adjustment_no 唯一; 含 L1/L2 审批 + 错误留痕)
--
-- 预置: t_tax_rate 种子 (CNY 6%, CNY 13%, USD 0%)，便于 4.3.2 启动期联调。
--
-- 不回滚: V29 仅 CREATE + INSERT，不 DROP / RENAME。
-- =============================================================

-- 1. t_exchange_rate: 汇率历史 (append-only, 多 source)
CREATE TABLE t_exchange_rate (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  from_currency CHAR(3) NOT NULL,
  to_currency CHAR(3) NOT NULL,
  rate DECIMAL(18,8) NOT NULL,
  rate_date DATE NOT NULL,
  source VARCHAR(32) NOT NULL,
  created_at DATETIME(3) NOT NULL,
  UNIQUE KEY uk_rate (from_currency, to_currency, rate_date, source)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='汇率历史';

-- 2. t_tax_rate: 全局税率
CREATE TABLE t_tax_rate (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  currency CHAR(3) NOT NULL,
  tax_rate DECIMAL(6,4) NOT NULL,
  effective_from DATE NOT NULL,
  effective_to DATE,
  created_at DATETIME(3) NOT NULL,
  KEY idx_tax_currency_eff (currency, effective_from)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='全局税率';

-- 3. t_billing_adjustment: 调账单
CREATE TABLE t_billing_adjustment (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  tenant_id BIGINT NOT NULL,
  adjustment_no VARCHAR(32) NOT NULL,
  source_invoice_id BIGINT NOT NULL,
  adjustment_type VARCHAR(16) NOT NULL,
  amount DECIMAL(18,4) NOT NULL,
  currency CHAR(3) NOT NULL,
  fx_rate_at_creation DECIMAL(18,8) NOT NULL,
  tax_rate DECIMAL(6,4) NOT NULL,
  gross_amount DECIMAL(18,4) NOT NULL,
  tax_amount DECIMAL(18,4) NOT NULL,
  net_amount DECIMAL(18,4) NOT NULL,
  reason VARCHAR(500) NOT NULL,
  status VARCHAR(32) NOT NULL,
  l1_approver_id BIGINT,
  l1_approved_at DATETIME(3),
  l1_comment VARCHAR(500),
  l2_approver_id BIGINT,
  l2_approved_at DATETIME(3),
  l2_comment VARCHAR(500),
  error_msg VARCHAR(1000),
  created_at DATETIME(3) NOT NULL,
  updated_at DATETIME(3) NOT NULL,
  UNIQUE KEY uk_adj_no (tenant_id, adjustment_no),
  KEY idx_adj_invoice (source_invoice_id),
  KEY idx_adj_status (tenant_id, status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='调账单';

-- 4. 预置 t_tax_rate (V29 启动数据)
INSERT INTO t_tax_rate (currency, tax_rate, effective_from, effective_to, created_at) VALUES
  ('CNY', 0.0600, '2026-01-01', NULL, NOW()),
  ('CNY', 0.1300, '2026-01-01', NULL, NOW()),
  ('USD', 0.0000, '2026-01-01', NULL, NOW());