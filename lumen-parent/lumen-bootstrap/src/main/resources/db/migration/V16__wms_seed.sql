-- =============================================================
-- V16: WMS 4 state machines seed (t_state_transition)
-- (Renumbered V15→V16 because V15 is wms_schema, V14 is audit_log_query_permission)
-- =============================================================
-- 4 state machines defined in spec §4:
--   WMS_INBOUND     : DRAFT → PENDING_APPROVAL → RECEIVING → PUTAWAY → DONE (+CANCELLED)
--   WMS_OUTBOUND    : DRAFT → PENDING_APPROVAL → PICKING → REVIEW → SHIPPED → DONE (+CANCELLED)
--   WMS_INV_ADJUST  : DRAFT → PENDING_APPROVAL → APPROVED → DONE (+REJECTED, +CANCELLED)
--   WMS_LOCATION    : ACTIVE ↔ FROZEN → DISABLED  (action-driven, no PENDING_APPROVAL)
--
-- Columns actually present in t_state_transition (V8):
--   id, state_machine_code, from_state, to_state, transition_name,
--   guard_expression, description, sort_order, tenant_id, created_at,
--   updated_at, deleted, version
--
-- Convention (per V9): tenant_id=0 means global row, sort_order=10 default.
-- guard_expression holds the required role code (e.g. "role:wms_clerk") in
--   lowercase form. sys_role.code stores uppercase codes (WMS_CLERK /
--   WMS_MANAGER / WMS_ADMIN, seeded in V17), so runtime resolution will
--   need a case-insensitive lookup when StateMachineEngine is implemented.
-- =============================================================

INSERT INTO t_state_transition
    (state_machine_code, from_state, to_state, transition_name, guard_expression, description, sort_order, tenant_id, created_at, updated_at, deleted, version)
VALUES
-- ----------------------------------------------------------------
-- WMS_INBOUND  (10 transitions — spec §4: 7 standard + 1 no-threshold submit + 2 mid-state cancels)
-- ----------------------------------------------------------------
('WMS_INBOUND',     'DRAFT',             'PENDING_APPROVAL', 'submit',             'role:wms_clerk',  '提交入库单（阈值超，需审批）',  10, 0, NOW(), NOW(), 0, 1),
('WMS_INBOUND',     'DRAFT',             'RECEIVING',        'submit_no_threshold','role:wms_clerk',  '提交入库单（未超阈值，直入收货）', 11, 0, NOW(), NOW(), 0, 1),
('WMS_INBOUND',     'PENDING_APPROVAL',  'RECEIVING',        'approve',            'role:wms_manager','审批通过入库单',               10, 0, NOW(), NOW(), 0, 1),
('WMS_INBOUND',     'PENDING_APPROVAL',  'DRAFT',            'reject',             'role:wms_manager','驳回入库单回草稿',             20, 0, NOW(), NOW(), 0, 1),
('WMS_INBOUND',     'RECEIVING',         'PUTAWAY',          'putaway',            'role:wms_clerk',  '收货完成→上架',                10, 0, NOW(), NOW(), 0, 1),
('WMS_INBOUND',     'PUTAWAY',           'DONE',             'complete',           'role:wms_clerk',  '上架完成→入库完成',            10, 0, NOW(), NOW(), 0, 1),
('WMS_INBOUND',     'DRAFT',             'CANCELLED',        'cancel',             'role:wms_clerk',  '草稿取消',                      30, 0, NOW(), NOW(), 0, 1),
('WMS_INBOUND',     'PENDING_APPROVAL',  'CANCELLED',        'cancel',             'role:wms_manager','审批前取消',                    30, 0, NOW(), NOW(), 0, 1),
('WMS_INBOUND',     'RECEIVING',         'CANCELLED',        'cancel',             'role:wms_manager','收货中取消',                    31, 0, NOW(), NOW(), 0, 1),
('WMS_INBOUND',     'PUTAWAY',           'CANCELLED',        'cancel',             'role:wms_manager','上架中取消',                    31, 0, NOW(), NOW(), 0, 1),

-- ----------------------------------------------------------------
-- WMS_OUTBOUND  (12 transitions — spec §4: 8 standard + 1 no-threshold submit + 3 mid-state cancels)
-- ----------------------------------------------------------------
('WMS_OUTBOUND',    'DRAFT',             'PENDING_APPROVAL', 'submit',             'role:wms_clerk',  '提交出库单（阈值超，需审批）',  10, 0, NOW(), NOW(), 0, 1),
('WMS_OUTBOUND',    'DRAFT',             'PICKING',          'submit_no_threshold','role:wms_clerk',  '提交出库单（未超阈值，直入拣货）', 11, 0, NOW(), NOW(), 0, 1),
('WMS_OUTBOUND',    'PENDING_APPROVAL',  'PICKING',          'approve',            'role:wms_manager','审批通过出库单',               10, 0, NOW(), NOW(), 0, 1),
('WMS_OUTBOUND',    'PENDING_APPROVAL',  'DRAFT',            'reject',             'role:wms_manager','驳回出库单回草稿',             20, 0, NOW(), NOW(), 0, 1),
('WMS_OUTBOUND',    'PICKING',           'REVIEW',           'review',             'role:wms_clerk',  '拣货完成→复核',                10, 0, NOW(), NOW(), 0, 1),
('WMS_OUTBOUND',    'REVIEW',            'SHIPPED',          'ship',               'role:wms_clerk',  '复核通过→发货',                10, 0, NOW(), NOW(), 0, 1),
('WMS_OUTBOUND',    'SHIPPED',           'DONE',             'complete',           'role:wms_clerk',  '发货完成→出库完成',            10, 0, NOW(), NOW(), 0, 1),
('WMS_OUTBOUND',    'DRAFT',             'CANCELLED',        'cancel',             'role:wms_clerk',  '草稿取消',                      30, 0, NOW(), NOW(), 0, 1),
('WMS_OUTBOUND',    'PENDING_APPROVAL',  'CANCELLED',        'cancel',             'role:wms_manager','审批前取消',                    30, 0, NOW(), NOW(), 0, 1),
('WMS_OUTBOUND',    'PICKING',           'CANCELLED',        'cancel',             'role:wms_manager','拣货中取消',                    31, 0, NOW(), NOW(), 0, 1),
('WMS_OUTBOUND',    'REVIEW',            'CANCELLED',        'cancel',             'role:wms_manager','复核中取消',                    31, 0, NOW(), NOW(), 0, 1),
('WMS_OUTBOUND',    'SHIPPED',           'CANCELLED',        'cancel',             'role:wms_manager','已发货撤销（异常冲销）',        31, 0, NOW(), NOW(), 0, 1),

-- ----------------------------------------------------------------
-- WMS_INV_ADJUST  (8 transitions — spec §4: 5 standard + 1 no-threshold submit + 2 mid-state cancels)
-- ----------------------------------------------------------------
('WMS_INV_ADJUST',  'DRAFT',             'PENDING_APPROVAL', 'submit',             'role:wms_clerk',  '提交调整单（阈值超，需审批）',  10, 0, NOW(), NOW(), 0, 1),
('WMS_INV_ADJUST',  'DRAFT',             'APPROVED',         'submit_no_threshold','role:wms_clerk',  '提交调整单（未超阈值，直入已审批）', 11, 0, NOW(), NOW(), 0, 1),
('WMS_INV_ADJUST',  'PENDING_APPROVAL',  'APPROVED',         'approve',            'role:wms_manager','审批通过调整单',               10, 0, NOW(), NOW(), 0, 1),
('WMS_INV_ADJUST',  'PENDING_APPROVAL',  'REJECTED',         'reject',             'role:wms_manager','驳回调整单',                   20, 0, NOW(), NOW(), 0, 1),
('WMS_INV_ADJUST',  'APPROVED',          'DONE',             'execute',            'role:wms_clerk',  '执行库存调整',                  10, 0, NOW(), NOW(), 0, 1),
('WMS_INV_ADJUST',  'DRAFT',             'CANCELLED',        'cancel',             'role:wms_clerk',  '草稿取消',                      30, 0, NOW(), NOW(), 0, 1),
('WMS_INV_ADJUST',  'PENDING_APPROVAL',  'CANCELLED',        'cancel',             'role:wms_manager','审批前取消',                    31, 0, NOW(), NOW(), 0, 1),
('WMS_INV_ADJUST',  'APPROVED',          'CANCELLED',        'cancel',             'role:wms_manager','已审批撤销（未执行）',          31, 0, NOW(), NOW(), 0, 1),

-- ----------------------------------------------------------------
-- WMS_LOCATION  (4 transitions — unchanged)
-- ----------------------------------------------------------------
('WMS_LOCATION',    'ACTIVE',            'FROZEN',           'freeze',    'role:wms_manager','冻结库位',                     10, 0, NOW(), NOW(), 0, 1),
('WMS_LOCATION',    'FROZEN',            'ACTIVE',           'unfreeze',  'role:wms_manager','解冻库位',                     10, 0, NOW(), NOW(), 0, 1),
('WMS_LOCATION',    'ACTIVE',            'DISABLED',         'disable',   'role:wms_admin',  '停用库位（终态）',              20, 0, NOW(), NOW(), 0, 1),
('WMS_LOCATION',    'FROZEN',            'DISABLED',         'disable',   'role:wms_admin',  '停用库位（终态）',              20, 0, NOW(), NOW(), 0, 1);