-- =============================================================
-- V1: WMS schema (subset needed for masterdata IT)
-- 摘自 lumen-bootstrap/src/main/resources/db/migration/V15__wms_schema.sql，
-- 这里只保留 4 个主数据表的最小定义（warehouse/location/item/customer），
-- 单据/库存/调整单等下游表在本组 IT 中不参与，留给后续 PR.
-- =============================================================

CREATE TABLE wms_warehouse (
    id          BIGINT          NOT NULL AUTO_INCREMENT,
    tenant_id   BIGINT          NOT NULL,
    code        VARCHAR(32)     NOT NULL,
    name        VARCHAR(128)    NOT NULL,
    type        VARCHAR(16)     NOT NULL DEFAULT 'SELF',
    status      VARCHAR(16)     NOT NULL DEFAULT 'ACTIVE',
    address     VARCHAR(255)    DEFAULT NULL,
    created_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted     TINYINT         NOT NULL DEFAULT 0,
    version     INT             NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    UNIQUE KEY uk_wms_warehouse_code (tenant_id, code),
    KEY idx_wms_warehouse_status (tenant_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='仓库';

CREATE TABLE wms_location (
    id          BIGINT          NOT NULL AUTO_INCREMENT,
    tenant_id   BIGINT          NOT NULL,
    warehouse_id BIGINT         NOT NULL,
    code        VARCHAR(32)     NOT NULL,
    zone        VARCHAR(16)     DEFAULT NULL,
    capacity    DECIMAL(18,4)   DEFAULT NULL,
    status      VARCHAR(16)     NOT NULL DEFAULT 'ACTIVE',
    created_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted     TINYINT         NOT NULL DEFAULT 0,
    version     INT             NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    UNIQUE KEY uk_wms_location_code (tenant_id, warehouse_id, code),
    KEY idx_wms_location_status (warehouse_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='库位';

CREATE TABLE wms_item (
    id              BIGINT          NOT NULL AUTO_INCREMENT,
    tenant_id       BIGINT          NOT NULL,
    sku             VARCHAR(64)     NOT NULL,
    name            VARCHAR(128)    NOT NULL,
    uom             VARCHAR(16)     NOT NULL,
    barcode         VARCHAR(64)     DEFAULT NULL,
    hazard_class    VARCHAR(16)     DEFAULT NULL,
    unit_cost       DECIMAL(18,4)   DEFAULT 0,
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted         TINYINT         NOT NULL DEFAULT 0,
    version         INT             NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    UNIQUE KEY uk_wms_item_sku (tenant_id, sku),
    KEY idx_wms_item_name (tenant_id, name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='货品';

CREATE TABLE wms_customer (
    id          BIGINT          NOT NULL AUTO_INCREMENT,
    tenant_id   BIGINT          NOT NULL,
    code        VARCHAR(32)     NOT NULL,
    name        VARCHAR(128)    NOT NULL,
    contact     VARCHAR(64)     DEFAULT NULL,
    phone       VARCHAR(32)     DEFAULT NULL,
    address     VARCHAR(255)    DEFAULT NULL,
    created_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted     TINYINT         NOT NULL DEFAULT 0,
    version     INT             NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    UNIQUE KEY uk_wms_customer_code (tenant_id, code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='客户';