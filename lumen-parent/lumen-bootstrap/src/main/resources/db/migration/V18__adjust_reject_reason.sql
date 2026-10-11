-- =============================================================
-- V18: 库存调整单 reject_reason 列 (PR5-T5.2)
-- =============================================================
-- T5.2 状态机 reject 动作需要记录驳回原因.
-- 沿用 InnoDB / utf8mb4 / 行 comment 风格.
-- 字段允许 NULL (V15 schema 未给列, 历史数据无值).

ALTER TABLE wms_inventory_adjust
    ADD COLUMN reject_reason VARCHAR(255) DEFAULT NULL
        COMMENT 'reject 动作写入的驳回原因'
        AFTER approved_at;
