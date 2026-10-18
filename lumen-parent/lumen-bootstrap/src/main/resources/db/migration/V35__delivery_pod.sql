-- =============================================================
-- V35: 拆出独立 wms_delivery_pod 表 (Iteration 4.5A.3 T3.2)
-- =============================================================
-- 背景:
--   4.5A.2 阶段 POD 数据存在 wms_delivery_order.sign_photo_url 字段 (V19);
--   4.5A.3 T3.2 拆出独立表, 支撑客户门户按 DO 查询 POD (含签名 base64 等扩展字段).
--
--   注: 配送单"签收时间"在 wms_delivery_order.delivered_at (V19), V19 schema 中
--   没有独立 signed_at 列. DeliveryOrderService.getDetailForCustomer 也是将
--   delivered_at 暴露为 signedAt (PR2 字段映射约定).

CREATE TABLE wms_delivery_pod (
    id                  BIGINT          NOT NULL AUTO_INCREMENT,
    tenant_id           BIGINT          NOT NULL                COMMENT '租户 ID',
    do_id               BIGINT          NOT NULL                COMMENT '关联配送单 (FK wms_delivery_order.id)',
    pod_type            VARCHAR(16)     NOT NULL DEFAULT 'PHOTO' COMMENT 'PHOTO | SIGNATURE | NONE',
    image_url           VARCHAR(512)    DEFAULT NULL            COMMENT '签收照片 URL (相对路径)',
    signature_base64    MEDIUMTEXT      DEFAULT NULL            COMMENT '手写签名 base64 (MEDIUMTEXT 因为签名可能较大)',
    signer_name         VARCHAR(64)     DEFAULT NULL            COMMENT '签收人姓名',
    signed_at           DATETIME        DEFAULT NULL            COMMENT '签收时间',
    created_at          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted             TINYINT         NOT NULL DEFAULT 0,
    version             INT             NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    UNIQUE KEY uk_wms_pod_do (do_id, deleted),                  -- 逻辑删除下, 一个 DO 最多一条活跃 POD
    KEY idx_wms_pod_tenant (tenant_id, signed_at)               -- 列表查询
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='签收凭证 (4.5A.3 拆出, 此前在 wms_delivery_order.sign_photo_url)';

-- Backfill: 从 wms_delivery_order.sign_photo_url 迁移
-- 仅 sign_required=1 且 sign_photo_url 非空 且 status='SIGNED' 的 DO;
-- 未签收的不造空 POD 行. delivered_at → signed_at 字段映射.
INSERT INTO wms_delivery_pod (tenant_id, do_id, pod_type, image_url, signed_at, created_at, updated_at, deleted, version)
SELECT d.tenant_id,
       d.id,
       'PHOTO',
       d.sign_photo_url,
       d.delivered_at,
       d.created_at,
       d.updated_at,
       0,
       1
FROM wms_delivery_order d
WHERE d.deleted = 0
  AND d.sign_required = 1
  AND d.sign_photo_url IS NOT NULL
  AND d.status = 'SIGNED';
