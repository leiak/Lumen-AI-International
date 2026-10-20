-- =============================================================
-- V36: 配送单加 planned_pickup_window_* 字段 (Iteration 4.5B.1)
-- =============================================================
-- 背景:
--   V19 创建 wms_delivery_order 时只有 planned_depart_at (调度员/系统回填);
--   4.5B 在客户门户加"选取件时间窗"功能, 需要 start/end 两个点表达商家期望
--   上门取件的窗口. 与 planned_depart_at 区别: 前者是商家约定, 后者是系统回填.
--
-- 注:
--   本文件 (V36) 为 4.5B 联合迁移, 后续 task 会向本文件追加:
--     block 2 (Task 1.2): t_customer_rate_override 合同价表
--     block 3 (Task 1.3): sys_permission + sys_role_permission 3 行 (RBAC)
--     block 4 (Task 1.3): sys_state_transition +1 行 (customer cancel)
--     block 5 (Task 2.5): sys_dict delivery_order_source_type.PORTAL 新枚举值
--   Flyway 不允许同一版本号拆成多文件, 故一个 V36 + 多 block.
--   本 task (4.5B.1 T1.1) 仅写 block 1, 后续 task 追加.
-- =============================================================

ALTER TABLE wms_delivery_order
    ADD COLUMN planned_pickup_window_start DATETIME NULL
        COMMENT '商家选取件窗口起点 (>= now+2h, < now+30d)' AFTER planned_depart_at,
    ADD COLUMN planned_pickup_window_end   DATETIME NULL
        COMMENT '商家选取件窗口终点 (> start, <= start+8h, 同日)' AFTER planned_pickup_window_start,
    ADD INDEX idx_do_pickup_window (tenant_id, planned_pickup_window_start);
