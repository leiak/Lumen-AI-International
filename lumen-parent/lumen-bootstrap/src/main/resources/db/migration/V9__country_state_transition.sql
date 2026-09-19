INSERT INTO t_state_transition (state_machine_code, from_state, to_state, transition_name, description, sort_order, tenant_id) VALUES
('sys_country', 'DRAFT',   'ACTIVE',  'publish',  '发布国家',  10, 0),
('sys_country', 'DRAFT',   'VOID',    'void',     '作废草稿',  20, 0),
('sys_country', 'ACTIVE',  'FROZEN',  'freeze',   '冻结启用',  10, 0),
('sys_country', 'ACTIVE',  'VOID',    'void',     '作废启用',  20, 0),
('sys_country', 'FROZEN',  'ACTIVE',  'unfreeze', '解冻',      10, 0),
('sys_country', 'FROZEN',  'VOID',    'void',     '作废冻结',  20, 0);
