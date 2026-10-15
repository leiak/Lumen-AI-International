-- Default SLA config (tenant_id=0) — see spec §3.6
INSERT INTO t_sla_config (tenant_id, severity, level, response_minutes, resolve_minutes, escalate_to_level) VALUES
  (0, 'HIGH',   'L1', 30,   240,  'L2'),
  (0, 'HIGH',   'L2', 30,   480,  'L3'),
  (0, 'HIGH',   'L3', 30,   1440, NULL),
  (0, 'MEDIUM', 'L1', 120,  1440, 'L2'),
  (0, 'MEDIUM', 'L2', 60,   2880, 'L3'),
  (0, 'MEDIUM', 'L3', 60,   4320, NULL),
  (0, 'LOW',    'L1', 480,  4320, 'L2'),
  (0, 'LOW',    'L2', 120,  7200, 'L3'),
  (0, 'LOW',    'L3', 120,  14400, NULL);