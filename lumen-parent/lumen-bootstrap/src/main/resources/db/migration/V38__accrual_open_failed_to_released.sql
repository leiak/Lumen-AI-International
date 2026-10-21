-- =============================================================
-- V38: billing_accrual 状态机补 OPEN_FAILED → RELEASED 边
--
-- 背景 (4.5C-1 设计阶段发现):
--   V26 (L212-220) 给 billing_accrual 种了 4 条边, 其中 OPEN_FAILED 是终点 ——
--   计费失败的应计一旦落进 OPEN_FAILED 就再也无法离开, 没有任何路径能把它清掉。
--
--   4.5C-1 要做的是: 客户在门户取消了订单, 如果这单的 accrual 当初因表达式
--   求值失败而落在 OPEN_FAILED, 取消之后这行仍然是"失败取证", 账单侧永远动不了它。
--
-- 处理:
--   补一条 OPEN_FAILED → RELEASED 边 (transition_name=releaseFailed), 让
--   AccrualServiceImpl.release 的状态机校验 (assertSmTransition, 4.5C-1 已改成
--   用 accrual.getStatus() 而非硬编码 OPEN) 在 OPEN_FAILED 状态下也能通过。
--
--   sort_order=13 接在 OPEN 的三条 (10/11/12) 之后, 与 20 组的 LOCKED 边隔开。
--   guard_expression='role:system' 沿用 V20/V22/V26 惯例 ——
--   StateMachineEngine.assertTransition 只校验边是否存在, 从不读这个字段。
--
--   不动 V26 本身 —— Flyway 已记录其 checksum, 改动会导致校验失败。
-- =============================================================

INSERT INTO t_state_transition
    (state_machine_code, from_state, to_state, transition_name, guard_expression, description, sort_order, tenant_id, created_at, updated_at, deleted, version)
VALUES
('billing_accrual', 'OPEN_FAILED', 'RELEASED', 'releaseFailed', 'role:system', '计费失败后释放', 13, 0, NOW(), NOW(), 0, 1)
ON DUPLICATE KEY UPDATE description = VALUES(description), sort_order = VALUES(sort_order);
