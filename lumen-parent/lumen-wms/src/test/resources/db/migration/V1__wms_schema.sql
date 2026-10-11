-- =============================================================
-- V1: WMS schema (subset needed for masterdata + inventory IT)
-- 摘自 lumen-bootstrap/src/main/resources/db/migration/V15__wms_schema.sql，
-- 这里保留 4 个主数据表的最小定义（warehouse/location/item/customer），
-- 以及 wms_inventory（PR2-T2.2 库存账本测试）。单据/调整单等下游表
-- 在本组 IT 中不参与，留给后续 PR.
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

CREATE TABLE wms_inventory (
    id                  BIGINT          NOT NULL AUTO_INCREMENT,
    tenant_id           BIGINT          NOT NULL,
    warehouse_id        BIGINT          NOT NULL,
    location_id         BIGINT          NOT NULL,
    item_id             BIGINT          NOT NULL,
    qty                 DECIMAL(18,4)   NOT NULL DEFAULT 0,
    locked_qty          DECIMAL(18,4)   NOT NULL DEFAULT 0,
    last_movement_at    DATETIME        DEFAULT CURRENT_TIMESTAMP,
    created_at          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted             TINYINT         NOT NULL DEFAULT 0,
    version             INT             NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    UNIQUE KEY uk_wms_inv_loc_item (tenant_id, warehouse_id, location_id, item_id),
    KEY idx_wms_inv_item (tenant_id, item_id, warehouse_id),
    CONSTRAINT chk_wms_inv_qty_nonneg CHECK (qty >= 0),
    CONSTRAINT chk_wms_inv_locked_nonneg CHECK (locked_qty >= 0),
    CONSTRAINT chk_wms_inv_qty_ge_locked CHECK (qty >= locked_qty)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='库存余额账本';