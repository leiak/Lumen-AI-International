-- =============================================================
-- V34: 给 wms_delivery_order 加 customer_id 字段
-- Iteration 4.5A.3 — 配送单 (DeliveryOrder) 客户门户按 customer 过滤
-- =============================================================
-- 背景:
--   V19 创建 wms_delivery_order 时没有 customer_id 列; V15 创建的
--   wms_outbound_note 已有 customer_id (NOT NULL). 4.5A.3 用 wms_delivery_order
--   作为客户门户的数据源, 需要加 customer_id 才能按 customer 过滤.
--
--   关系链:  delivery_order.source_no = outbound_note.no
--   (V19 schema 注释: '来源单号 (outbound.shipped -> outbound_no)')

ALTER TABLE wms_delivery_order
    ADD COLUMN customer_id BIGINT NULL COMMENT '客户 ID (4.5A.3 新增; 从 wms_outbound_note 关联)' AFTER tenant_id;

-- 索引: 客户门户 list 走 (tenant_id, customer_id) 过滤, 复合索引左前缀
-- 同时覆盖 tenant 维度的过滤场景
CREATE INDEX idx_wms_delivery_order_tenant_customer ON wms_delivery_order (tenant_id, customer_id);

-- Backfill: 从 wms_outbound_note.customer_id 关联
-- 注: 4.5A.3 时已有 outbound_note 全部带 customer_id (V15 schema 列 NOT NULL).
-- 旧 delivery_order (无对应 outbound_note) 允许 customer_id 保持 NULL.
UPDATE wms_delivery_order d
JOIN wms_outbound_note o
    ON o.tenant_id  = d.tenant_id
   AND o.no         = d.source_no
   AND o.deleted    = 0
SET d.customer_id  = o.customer_id
WHERE d.deleted    = 0
  AND d.customer_id IS NULL;
