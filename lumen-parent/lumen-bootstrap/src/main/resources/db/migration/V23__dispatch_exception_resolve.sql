-- =============================================================
-- V23: Dispatch exception out-transitions (t_state_transition)
-- Iteration 4.1.x PR1
-- =============================================================
-- Add 2 new DISPATCH_ORDER out-transitions from EXCEPTION so the
-- resolveException flow (spec §10 PR5 follow-up) can move a stranded
-- order either back to DISPATCHED (re-dispatch with a fresh vehicle)
-- or to CANCELLED (terminal close-out).
--
-- Columns match V20__dispatch_seed.sql INSERT format and t_state_transition
-- schema defined in V8__state_transition.sql:
--   state_machine_code, from_state, to_state, transition_name, guard_expression,
--   description, sort_order, tenant_id, created_at, updated_at, deleted, version
--
-- Guard: dispatcher / dispatch_admin only (re-dispatch & exceptionCancel are
-- admin actions; the assign perm already covers both roles).
--
-- tenant_id=0 means global row (matches V20/V22 convention).
-- sort_order=100/101 to keep "reDispatch" before "exceptionCancel" in UI dropdowns.
-- =============================================================

INSERT INTO t_state_transition
    (state_machine_code, from_state, to_state, transition_name, guard_expression, description, sort_order, tenant_id, created_at, updated_at, deleted, version)
VALUES
('DISPATCH_ORDER', 'EXCEPTION', 'DISPATCHED', 'reDispatch',       'role:dispatch_admin OR role:dispatcher', '异常工单处理后重新派车',        100, 0, NOW(), NOW(), 0, 1),
('DISPATCH_ORDER', 'EXCEPTION', 'CANCELLED', 'exceptionCancel',   'role:dispatch_admin OR role:dispatcher', '异常工单处理后直接取消',      101, 0, NOW(), NOW(), 0, 1)
;
