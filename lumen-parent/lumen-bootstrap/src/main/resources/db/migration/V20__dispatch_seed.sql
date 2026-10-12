-- =============================================================
-- V20: Dispatch state machine seeds (t_state_transition)
-- Iteration 4.1 PR2
-- =============================================================
-- 3 state machines = 18 distinct transition names / 29 rows:
--   DISPATCH_ORDER         (12 rows: 5 forward + 4 cancel + 3 exception)
--   VEHICLE                (10 rows: 5 forward + 5 'out' suspend)
--   EXCEPTION_WORK_ORDER   ( 7 rows: 4 forward + 3 cancel)
--
-- Columns actually present in t_state_transition (V8):
--   id, state_machine_code, from_state, to_state, transition_name,
--   guard_expression, description, sort_order, tenant_id, created_at,
--   updated_at, deleted, version
--
-- Convention (per V16/V20): tenant_id=0 means global row, sort_order=10 default.
-- guard_expression holds the required role code in lowercase form
--   (e.g. "role:dispatcher", "role:dispatch_admin", "role:driver",
--    "role:customer_service"). sys_role.code stores uppercase codes
--   (DISPATCHER / DISPATCH_ADMIN / DRIVER / CUSTOMER_SERVICE, seeded in V21),
--   so runtime resolution needs a case-insensitive lookup when
--   StateMachineEngine is implemented (PR5).
-- =============================================================

INSERT INTO t_state_transition
    (state_machine_code, from_state, to_state, transition_name, guard_expression, description, sort_order, tenant_id, created_at, updated_at, deleted, version)
VALUES
-- ----------------------------------------------------------------
-- DISPATCH_ORDER  (12 rows — spec §4: 7 distinct transitions)
--   forward (5):  assign, load, depart, arrive, sign
--   cancel  (4):  PENDING_DISPATCH, DISPATCHED, LOADED, ENROUTE → CANCELLED
--   exception(3): LOADED, ENROUTE, ARRIVED → EXCEPTION
-- ----------------------------------------------------------------
('DISPATCH_ORDER', 'PENDING_DISPATCH', 'DISPATCHED', 'assign',    'role:dispatcher',     '派车成功',                              10, 0, NOW(), NOW(), 0, 1),
('DISPATCH_ORDER', 'DISPATCHED',       'LOADED',     'load',      'role:dispatcher',     '提货/装车回执',                        10, 0, NOW(), NOW(), 0, 1),
('DISPATCH_ORDER', 'LOADED',           'ENROUTE',    'depart',    'role:driver',         '出发',                                10, 0, NOW(), NOW(), 0, 1),
('DISPATCH_ORDER', 'ENROUTE',          'ARRIVED',    'arrive',    'role:driver',         '送达登记',                            10, 0, NOW(), NOW(), 0, 1),
('DISPATCH_ORDER', 'ARRIVED',          'SIGNED',     'sign',      'role:driver',         '签收回执',                            10, 0, NOW(), NOW(), 0, 1),
('DISPATCH_ORDER', 'PENDING_DISPATCH', 'CANCELLED',  'cancel',    'role:dispatcher',     '调度前取消',                          30, 0, NOW(), NOW(), 0, 1),
('DISPATCH_ORDER', 'DISPATCHED',       'CANCELLED',  'cancel',    'role:dispatch_admin', '已派车撤销',                          31, 0, NOW(), NOW(), 0, 1),
('DISPATCH_ORDER', 'LOADED',           'CANCELLED',  'cancel',    'role:dispatch_admin', '装载后撤销',                          31, 0, NOW(), NOW(), 0, 1),
('DISPATCH_ORDER', 'ENROUTE',          'CANCELLED',  'cancel',    'role:dispatch_admin', '在途撤销（异常冲销）',                 31, 0, NOW(), NOW(), 0, 1),
('DISPATCH_ORDER', 'LOADED',           'EXCEPTION',  'exception', 'role:dispatcher',     '装载后转异常工单',                    40, 0, NOW(), NOW(), 0, 1),
('DISPATCH_ORDER', 'ENROUTE',          'EXCEPTION',  'exception', 'role:dispatcher',     '在途异常',                            40, 0, NOW(), NOW(), 0, 1),
('DISPATCH_ORDER', 'ARRIVED',          'EXCEPTION',  'exception', 'role:dispatcher',     '送达后异常',                          40, 0, NOW(), NOW(), 0, 1),

-- ----------------------------------------------------------------
-- VEHICLE  (10 rows — spec §4: 6 distinct transitions)
--   forward (5): dispatch, load, depart, returning, idle
--   'out'   (5): IDLE, DISPATCHED, LOADING, ENROUTE, RETURNING → OUT (suspend)
-- ----------------------------------------------------------------
('VEHICLE', 'IDLE',       'DISPATCHED', 'dispatch',  'role:dispatch_admin', '派车',                                10, 0, NOW(), NOW(), 0, 1),
('VEHICLE', 'DISPATCHED', 'LOADING',    'load',      'role:dispatcher',     '装载',                                10, 0, NOW(), NOW(), 0, 1),
('VEHICLE', 'LOADING',    'ENROUTE',    'depart',    'role:dispatcher',     '出发',                                10, 0, NOW(), NOW(), 0, 1),
('VEHICLE', 'ENROUTE',    'RETURNING',  'returning', 'role:driver',         '开始回程',                            10, 0, NOW(), NOW(), 0, 1),
('VEHICLE', 'RETURNING',  'IDLE',       'idle',      'role:dispatcher',     '回场闲置',                            10, 0, NOW(), NOW(), 0, 1),
('VEHICLE', 'IDLE',       'OUT',        'out',       'role:dispatch_admin', '车辆停用（IDLE→OUT）',                20, 0, NOW(), NOW(), 0, 1),
('VEHICLE', 'DISPATCHED', 'OUT',        'out',       'role:dispatch_admin', '车辆停用（DISPATCHED→OUT）',          20, 0, NOW(), NOW(), 0, 1),
('VEHICLE', 'LOADING',    'OUT',        'out',       'role:dispatch_admin', '车辆停用（LOADING→OUT）',             20, 0, NOW(), NOW(), 0, 1),
('VEHICLE', 'ENROUTE',    'OUT',        'out',       'role:dispatch_admin', '车辆停用（ENROUTE→OUT）',             20, 0, NOW(), NOW(), 0, 1),
('VEHICLE', 'RETURNING',  'OUT',        'out',       'role:dispatch_admin', '车辆停用（RETURNING→OUT）',           20, 0, NOW(), NOW(), 0, 1),

-- ----------------------------------------------------------------
-- EXCEPTION_WORK_ORDER  (7 rows — spec §4: 5 distinct transitions)
--   forward (4): investigate, resolve, close, reopen
--   cancel  (3): OPEN, INVESTIGATING, RESOLVED → CANCELLED
--   guard_expression: 客服 (customer_service) 处理工单，
--                     调度管理员 (dispatch_admin) 才能 cancel
-- ----------------------------------------------------------------
('EXCEPTION_WORK_ORDER', 'OPEN',          'INVESTIGATING', 'investigate', 'role:customer_service', '调查异常',           10, 0, NOW(), NOW(), 0, 1),
('EXCEPTION_WORK_ORDER', 'INVESTIGATING', 'RESOLVED',      'resolve',     'role:customer_service', '解决异常',           10, 0, NOW(), NOW(), 0, 1),
('EXCEPTION_WORK_ORDER', 'RESOLVED',      'CLOSED',        'close',       'role:customer_service', '关闭异常',           10, 0, NOW(), NOW(), 0, 1),
('EXCEPTION_WORK_ORDER', 'CLOSED',        'OPEN',          'reopen',      'role:customer_service', '重新打开',           20, 0, NOW(), NOW(), 0, 1),
('EXCEPTION_WORK_ORDER', 'OPEN',          'CANCELLED',     'cancel',      'role:dispatch_admin',  '工单撤销',           30, 0, NOW(), NOW(), 0, 1),
('EXCEPTION_WORK_ORDER', 'INVESTIGATING', 'CANCELLED',     'cancel',      'role:dispatch_admin',  '调查中撤销',         31, 0, NOW(), NOW(), 0, 1),
('EXCEPTION_WORK_ORDER', 'RESOLVED',      'CANCELLED',     'cancel',      'role:dispatch_admin',  '已解决撤销',         31, 0, NOW(), NOW(), 0, 1)
;
