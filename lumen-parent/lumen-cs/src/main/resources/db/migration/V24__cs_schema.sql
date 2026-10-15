-- ============================================================
-- V24 — Customer service (CS) module: SLA + escalation
-- Iteration 4.2 PR1
-- ============================================================

CREATE TABLE t_sla_config (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  tenant_id BIGINT NOT NULL,
  severity VARCHAR(16) NOT NULL,
  level VARCHAR(16) NOT NULL,
  response_minutes INT NOT NULL,
  resolve_minutes INT NOT NULL,
  escalate_to_level VARCHAR(16),
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted TINYINT NOT NULL DEFAULT 0,
  version INT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_tenant_severity_level (tenant_id, severity, level, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE t_business_calendar (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  tenant_id BIGINT NOT NULL,
  weekday TINYINT NOT NULL,
  start_time TIME NOT NULL,
  end_time TIME NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted TINYINT NOT NULL DEFAULT 0,
  version INT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_tenant_weekday_start (tenant_id, weekday, start_time, deleted),
  CHECK (weekday BETWEEN 1 AND 7),
  CHECK (end_time > start_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE t_holiday (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  tenant_id BIGINT NOT NULL,
  holiday_date DATE NOT NULL,
  description VARCHAR(128),
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted TINYINT NOT NULL DEFAULT 0,
  version INT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_tenant_date (tenant_id, holiday_date, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE t_sla_event (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  tenant_id BIGINT NOT NULL,
  exception_id BIGINT NOT NULL,
  event_type VARCHAR(32) NOT NULL,
  current_level VARCHAR(16) NOT NULL,
  severity VARCHAR(16) NOT NULL,
  occurred_at DATETIME(3) NOT NULL,
  payload JSON,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  deleted TINYINT NOT NULL DEFAULT 0,
  version INT NOT NULL DEFAULT 0,
  KEY idx_exception (exception_id),
  KEY idx_tenant_occurred (tenant_id, occurred_at),
  UNIQUE KEY uk_exception_event_target (exception_id, event_type, current_level, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;