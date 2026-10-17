-- =============================================================
-- V30 — Iteration 4.3.2 PR1: 多币种字段 + L2 终审 + finance_manager 角色
-- =============================================================
-- 背景: 4.3.2 在 4.3.1 (V26/V27/V28/V29) 基础上:
--   - t_billing_invoice / t_billing_accrual 加 fx_rate_at_creation 字段,
--     并在 t_billing_invoice 加 L2 审批三件套 (approver/approved_at/comment).
--   - 单据状态机从 1 级 (L1) 升级为 2 级 (L1 + L2): PENDING_L1 + L1_APPROVE
--     的终态从 L1_APPROVED 改为 PENDING_L2 (破坏性变更, 但 4.3.2 启动期).
--   - 新增 billing_adjustment 状态机 (调账单两极审批 + 过账).
--   - 新增 10 个权限 (id 140..149) + 1 个财务经理角色 (id 13) + 跨角色绑定.
--
-- 内容:
--   1. t_billing_invoice   加 4 列 (fx_rate_at_creation + l2_*) — currency 已在 V26, 跳过
--   2. t_billing_accrual   加 1 列 (fx_rate_at_creation) — currency 已在 V26, 跳过
--   3. t_state_transition  UPDATE 1 行 (billing_invoice L1_APPROVE -> PENDING_L2)
--   4. t_state_transition  INSERT 2 行 (billing_invoice L2 transitions)
--   5. t_state_transition  INSERT 7 行 (billing_adjustment 全状态机)
--   6. sys_permission      +10 行 (id 140..149)
--   7. sys_role            +1 行 (id 13 finance_manager)
--   8. sys_role_permission +12 行 (finance_manager 绑定)
--   9. sys_role_permission +5 行 (finance_ops 补调账 + 基础视图)
--  10. sys_role_permission +2 行 (finance_reviewer 补调账审批)
--
-- 当前 id 占用 (V29 末态, 不含 sys_role_permission):
--   sys_permission        上限 139  (V27 末)
--   sys_role              上限  12  (V26 末)
--   sys_role_permission   上限 287  (V27 末)
--   t_state_transition    独立 id 列  (按 sm_code/from_state/to_state 唯一)
-- 本次 +10/+1/+19/+9 (调整后) 后上限 = 149 / 13 / 306 / +9. 安全, 无冲突.
--
-- 重要的偏差说明 (与 4.3.2 PR1 task spec 对照):
--   - spec 原计划 t_billing_invoice/t_billing_accrual 加 currency 列, 但 V26
--     已经把 currency CHAR(3) NOT NULL DEFAULT 'CNY' 加到两张表里了, 再 ADD
--     会触发 "Duplicate column name". 本迁移只补 fx_rate_at_creation, 并按
--     l1_* 的现有位置加 l2_*.
--   - spec 原计划 t_state_transition INSERT 用 (sm_code, event, guard_expr,
--     tenant_scope) — 这些列名在 V8 创建的 t_state_transition 中不存在;
--     实际列是 (state_machine_code, transition_name, guard_expression,
--     tenant_id). 本迁移按真实列名写, 并补齐 spec 缺失的 description /
--     sort_order / updated_at / deleted / version 列.
--   - spec sys_role_permission INSERT 没给主键 id, 但 sys_role_permission.id
--     是 NOT NULL 主键. 本迁移补显式 id (288 起, 接着 V27 的 287).
-- =============================================================

-- ------------------------------ 1. t_billing_invoice 加列 ------------------------------

-- spec 中 5 列里: currency 已在 V26 (Duplicate column name, 跳过).
-- 保留 fx_rate_at_creation, 沿用现有 currency 列旁放置; l2_* 紧接 l1_*.
ALTER TABLE t_billing_invoice
  ADD COLUMN fx_rate_at_creation DECIMAL(18,8) NULL AFTER currency,
  ADD COLUMN l2_approver_id BIGINT NULL AFTER l1_approver_id,
  ADD COLUMN l2_approved_at DATETIME(3) NULL AFTER l1_approved_at,
  ADD COLUMN l2_comment VARCHAR(500) NULL AFTER l1_comment
;

-- ------------------------------ 2. t_billing_accrual 加列 ------------------------------

-- currency 已在 V26, 跳过; 仅加 fx_rate_at_creation.
ALTER TABLE t_billing_accrual
  ADD COLUMN fx_rate_at_creation DECIMAL(18,8) NULL AFTER currency
;

-- ------------------------------ 3. 更新 billing_invoice 状态机 (4.3.1 -> 4.3.2) ------------------------------

-- V26 建的 (billing_invoice, PENDING_L1, L1_APPROVED) 终态改成 PENDING_L2.
-- 注意唯一键是 (state_machine_code, from_state, to_state, tenant_id),
-- UPDATE 改 to_state 后新键 = (billing_invoice, PENDING_L1, PENDING_L2, 0),
-- 与 V26 的 L1_APPROVED/REJECTED 行不冲突, 安全.
UPDATE t_state_transition
SET to_state = 'PENDING_L2'
WHERE state_machine_code = 'billing_invoice'
  AND from_state = 'PENDING_L1'
  AND to_state = 'L1_APPROVED'
  AND tenant_id = 0
;

-- ------------------------------ 4. 新增 billing_invoice L2 transitions ------------------------------

INSERT INTO t_state_transition
    (state_machine_code, from_state, to_state, transition_name, guard_expression, description, sort_order, tenant_id, created_at, updated_at, deleted, version)
VALUES
('billing_invoice', 'PENDING_L2', 'L2_APPROVED', 'l2_approve', 'role:finance_manager', '财务经理终审通过', 30, 0, NOW(), NOW(), 0, 1),
('billing_invoice', 'PENDING_L2', 'REJECTED',    'l2_reject',  'role:finance_manager &amp;&amp; comment!=null', '财务经理终审驳回', 31, 0, NOW(), NOW(), 0, 1)
;

-- ------------------------------ 5. 新增 billing_adjustment 状态机 ------------------------------

-- 7 行完整状态机:
--   DRAFT      -> PENDING_L1  (SUBMIT)
--   PENDING_L1 -> PENDING_L2  (L1_APPROVE)
--   PENDING_L1 -> REJECTED    (L1_REJECT)
--   PENDING_L2 -> L2_APPROVED (L2_APPROVE)
--   PENDING_L2 -> REJECTED    (L2_REJECT)
--   L2_APPROVED -> POSTED    (MARK_POSTED)
--   REJECTED   -> DRAFT       (OPERATOR_ACTION)
INSERT INTO t_state_transition
    (state_machine_code, from_state, to_state, transition_name, guard_expression, description, sort_order, tenant_id, created_at, updated_at, deleted, version)
VALUES
('billing_adjustment', 'DRAFT',      'PENDING_L1', 'submit',         'role:finance_ops',      '运营提交调账单初审',   10, 0, NOW(), NOW(), 0, 1),
('billing_adjustment', 'PENDING_L1', 'PENDING_L2', 'l1_approve',     'role:finance_reviewer', '财务初审 -> 终审',    20, 0, NOW(), NOW(), 0, 1),
('billing_adjustment', 'PENDING_L1', 'REJECTED',   'l1_reject',      'role:finance_reviewer &amp;&amp; comment!=null', '财务初审驳回', 21, 0, NOW(), NOW(), 0, 1),
('billing_adjustment', 'PENDING_L2', 'L2_APPROVED','l2_approve',     'role:finance_manager',  '财务经理终审通过',    30, 0, NOW(), NOW(), 0, 1),
('billing_adjustment', 'PENDING_L2', 'REJECTED',   'l2_reject',      'role:finance_manager &amp;&amp; comment!=null', '财务经理终审驳回', 31, 0, NOW(), NOW(), 0, 1),
('billing_adjustment', 'L2_APPROVED','POSTED',     'mark_posted',    'role:finance_manager',  '财务经理确认过账',    40, 0, NOW(), NOW(), 0, 1),
('billing_adjustment', 'REJECTED',   'DRAFT',      'operator_action','role:finance_ops',      '驳回后运营可重提',    50, 0, NOW(), NOW(), 0, 1)
;

-- ------------------------------ 6. RBAC: 新增 10 个权限 (id 140..149) ------------------------------

-- spec 列名为 (id, parent_id, code, name, type, path, sort_order, created_at),
-- 但 V2 schema 要求 (tenant_id, status, version, deleted) — 补齐.
-- 注意: V26 给 finance:* 用的 type 全是 'API', 这里遵从同一惯例
-- (controller @PreAuthorize hasAuthority 与 API 类型对齐).
INSERT INTO sys_permission (id, tenant_id, parent_id, code, type, name, path, sort_order, status, version, deleted, created_at, updated_at)
VALUES
(140, 1, NULL, 'finance:invoice:l2_approve',    'API', 'L2 财务终审',     '/finance/invoices',         1011, 1, 0, 0, NOW(), NOW()),
(141, 1, NULL, 'finance:rate:view',             'API', '汇率查看',         '/finance/rates',            1012, 1, 0, 0, NOW(), NOW()),
(142, 1, NULL, 'finance:rate:edit',             'API', '汇率手动录入',     '/finance/rates',            1013, 1, 0, 0, NOW(), NOW()),
(143, 1, NULL, 'finance:taxrate:view',          'API', '税率查看',         '/finance/tax-rates',        1014, 1, 0, 0, NOW(), NOW()),
(144, 1, NULL, 'finance:taxrate:edit',          'API', '税率编辑',         '/finance/tax-rates',        1015, 1, 0, 0, NOW(), NOW()),
(145, 1, NULL, 'finance:adjustment:view',       'API', '调账单查看',       '/finance/adjustments',      1016, 1, 0, 0, NOW(), NOW()),
(146, 1, NULL, 'finance:adjustment:create',     'API', '调账单创建',       '/finance/adjustments',      1017, 1, 0, 0, NOW(), NOW()),
(147, 1, NULL, 'finance:adjustment:submit',     'API', '调账单提交初审',   '/finance/adjustments',      1018, 1, 0, 0, NOW(), NOW()),
(148, 1, NULL, 'finance:adjustment:l1_approve', 'API', '调账单 L1 审批',   '/finance/adjustments',      1019, 1, 0, 0, NOW(), NOW()),
(149, 1, NULL, 'finance:adjustment:l2_approve', 'API', '调账单 L2 终审',   '/finance/adjustments',      1020, 1, 0, 0, NOW(), NOW())
ON DUPLICATE KEY UPDATE
    name = VALUES(name),
    path = VALUES(path),
    sort_order = VALUES(sort_order)
;

-- ------------------------------ 7. RBAC: 新增 finance_manager 角色 (id 13) ------------------------------

-- spec 给的是 (id, code, name, description, status, created_at), 但 V2 schema
-- 要求 (tenant_id, version, deleted) — 补齐.
INSERT INTO sys_role (id, tenant_id, code, name, description, status, version, deleted, created_at, updated_at)
VALUES
(13, 1, 'finance_manager', '财务经理', 'L2 终审 + 汇率/税率管理 + 调账终审', 1, 0, 0, NOW(), NOW())
ON DUPLICATE KEY UPDATE
    name = VALUES(name),
    description = VALUES(description)
;

-- ------------------------------ 8. RBAC: finance_manager 绑 12 个权限 ------------------------------

-- V27 末 sys_role_permission 上限 287, 从 288 起.
-- spec 用 (role_id, permission_id, tenant_id, created_at) 但 sys_role_permission
-- 主键 id NOT NULL, 补显式 id.
-- 12 行: 288..299
INSERT INTO sys_role_permission (id, role_id, permission_id, tenant_id, created_at) VALUES
(288, 13, 130, 1, NOW()),  -- finance:rule:view
(289, 13, 131, 1, NOW()),  -- finance:rule:edit
(290, 13, 132, 1, NOW()),  -- finance:invoice:view
(291, 13, 134, 1, NOW()),  -- finance:invoice:l1_approve
(292, 13, 140, 1, NOW()),  -- finance:invoice:l2_approve  (新)
(293, 13, 135, 1, NOW()),  -- finance:report:view
(294, 13, 141, 1, NOW()),  -- finance:rate:view  (新)
(295, 13, 142, 1, NOW()),  -- finance:rate:edit  (新)
(296, 13, 143, 1, NOW()),  -- finance:taxrate:view  (新)
(297, 13, 144, 1, NOW()),  -- finance:taxrate:edit  (新)
(298, 13, 145, 1, NOW()),  -- finance:adjustment:view  (新)
(299, 13, 149, 1, NOW())   -- finance:adjustment:l2_approve  (新)
ON DUPLICATE KEY UPDATE
    role_id = VALUES(role_id),
    permission_id = VALUES(permission_id),
    tenant_id = VALUES(tenant_id)
;

-- ------------------------------ 9. RBAC: finance_ops (id 11) 加 5 个调账 + 基础权限 ------------------------------

-- 5 行: 300..304
INSERT INTO sys_role_permission (id, role_id, permission_id, tenant_id, created_at) VALUES
(300, 11, 141, 1, NOW()),  -- finance:rate:view
(301, 11, 143, 1, NOW()),  -- finance:taxrate:view
(302, 11, 145, 1, NOW()),  -- finance:adjustment:view
(303, 11, 146, 1, NOW()),  -- finance:adjustment:create
(304, 11, 147, 1, NOW())   -- finance:adjustment:submit
ON DUPLICATE KEY UPDATE
    role_id = VALUES(role_id),
    permission_id = VALUES(permission_id),
    tenant_id = VALUES(tenant_id)
;

-- ------------------------------ 10. RBAC: finance_reviewer (id 12) 加 2 个调账权限 ------------------------------

-- 2 行: 305..306
INSERT INTO sys_role_permission (id, role_id, permission_id, tenant_id, created_at) VALUES
(305, 12, 145, 1, NOW()),  -- finance:adjustment:view
(306, 12, 148, 1, NOW())   -- finance:adjustment:l1_approve
ON DUPLICATE KEY UPDATE
    role_id = VALUES(role_id),
    permission_id = VALUES(permission_id),
    tenant_id = VALUES(tenant_id)
;

-- =============================================================
-- 备注:
--   - 本迁移对 6 张表/schema 改动:
--       t_billing_invoice          +4 列 (fx_rate + l2_*)
--       t_billing_accrual          +1 列 (fx_rate)
--       t_state_transition         UPDATE 1 行 / INSERT 9 行 (billing_invoice L2 + billing_adjustment)
--       sys_permission             +10 行 (id 140..149)
--       sys_role                   +1 行 (id 13 finance_manager)
--       sys_role_permission        +19 行 (id 288..306)
--   - guard_expression 为 documentary, runtime 由 controller @PreAuthorize 守门
--     (与 V25/V26/V27 备注一致).
--   - ALTER 后 fx_rate_at_creation 默认为 NULL, 4.3.2 PR 起的单据 / 暂估单写入时
--     由 BillingInvoiceService / AccrualService 回填 (4.3.2 PR2/PR3 范围).
--   - 4.3.1 -> 4.3.2 状态机破坏性变更: L1_APPROVED 终态改成 PENDING_L2. 4.3.1
--     已 L1_APPROVED 的单据在 4.3.2 启动期需要走一次 "回退重审" 流程 (不在本迁
--     移范围, 由 4.3.2 PR3 处理).
-- =============================================================
