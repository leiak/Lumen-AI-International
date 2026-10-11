-- =============================================================
-- V15: WMS schema (12 tables + indexes + unique constraints)
-- (Renumbered from V14 → V15 because V14 is already taken by V14__audit_log_query_permission.sql)
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

CREATE TABLE wms_inbound_note (
    id          BIGINT          NOT NULL AUTO_INCREMENT,
    tenant_id   BIGINT          NOT NULL,
    no          VARCHAR(32)     NOT NULL,
    customer_id BIGINT          NOT NULL,
    warehouse_id BIGINT         NOT NULL,
    status      VARCHAR(20)     NOT NULL DEFAULT 'DRAFT',
    arrived_at  DATETIME        DEFAULT NULL,
    completed_at DATETIME       DEFAULT NULL,
    remark      VARCHAR(500)    DEFAULT NULL,
    created_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted     TINYINT         NOT NULL DEFAULT 0,
    version     INT             NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    UNIQUE KEY uk_wms_inbound_no (tenant_id, no),
    KEY idx_wms_inbound_status (tenant_id, status),
    KEY idx_wms_inbound_wh (tenant_id, warehouse_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='入库单';

CREATE TABLE wms_inbound_line (
    id                      BIGINT          NOT NULL AUTO_INCREMENT,
    tenant_id               BIGINT          NOT NULL,
    note_id                 BIGINT          NOT NULL,
    line_no                 INT             NOT NULL,
    item_id                 BIGINT          NOT NULL,
    qty                     DECIMAL(18,4)   NOT NULL,
    received_qty            DECIMAL(18,4)   NOT NULL DEFAULT 0,
    putaway_location_id     BIGINT          DEFAULT NULL,
    putaway_qty             DECIMAL(18,4)   NOT NULL DEFAULT 0,
    created_at              DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted                 TINYINT         NOT NULL DEFAULT 0,
    version                 INT             NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    UNIQUE KEY uk_wms_inb_line (note_id, line_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='入库单行';

CREATE TABLE wms_outbound_note (
    id              BIGINT          NOT NULL AUTO_INCREMENT,
    tenant_id       BIGINT          NOT NULL,
    no              VARCHAR(32)     NOT NULL,
    customer_id     BIGINT          NOT NULL,
    warehouse_id    BIGINT          NOT NULL,
    status          VARCHAR(20)     NOT NULL DEFAULT 'DRAFT',
    requested_at    DATETIME        DEFAULT NULL,
    shipped_at      DATETIME        DEFAULT NULL,
    carrier         VARCHAR(64)     DEFAULT NULL,
    tracking_no     VARCHAR(64)     DEFAULT NULL,
    remark          VARCHAR(500)    DEFAULT NULL,
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted         TINYINT         NOT NULL DEFAULT 0,
    version         INT             NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    UNIQUE KEY uk_wms_out_no (tenant_id, no),
    KEY idx_wms_out_status (tenant_id, status),
    KEY idx_wms_out_wh (tenant_id, warehouse_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='出库单';

CREATE TABLE wms_outbound_line (
    id                      BIGINT          NOT NULL AUTO_INCREMENT,
    tenant_id               BIGINT          NOT NULL,
    note_id                 BIGINT          NOT NULL,
    line_no                 INT             NOT NULL,
    item_id                 BIGINT          NOT NULL,
    qty                     DECIMAL(18,4)   NOT NULL,
    picked_qty              DECIMAL(18,4)   NOT NULL DEFAULT 0,
    source_location_id      BIGINT          DEFAULT NULL,
    created_at              DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted                 TINYINT         NOT NULL DEFAULT 0,
    version                 INT             NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    UNIQUE KEY uk_wms_out_line (note_id, line_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='出库单行';

CREATE TABLE wms_inventory_adjust (
    id              BIGINT          NOT NULL AUTO_INCREMENT,
    tenant_id       BIGINT          NOT NULL,
    no              VARCHAR(32)     NOT NULL,
    warehouse_id    BIGINT          NOT NULL,
    status          VARCHAR(20)     NOT NULL DEFAULT 'DRAFT',
    reason          VARCHAR(64)     NOT NULL,
    approved_by     BIGINT          DEFAULT NULL,
    approved_at     DATETIME        DEFAULT NULL,
    remark          VARCHAR(500)    DEFAULT NULL,
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted         TINYINT         NOT NULL DEFAULT 0,
    version         INT             NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    UNIQUE KEY uk_wms_adj_no (tenant_id, no),
    KEY idx_wms_adj_status (tenant_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='库存调整单';

CREATE TABLE wms_inventory_adjust_line (
    id              BIGINT          NOT NULL AUTO_INCREMENT,
    tenant_id       BIGINT          NOT NULL,
    adjust_id       BIGINT          NOT NULL,
    line_no         INT             NOT NULL,
    item_id         BIGINT          NOT NULL,
    location_id     BIGINT          NOT NULL,
    delta_qty       DECIMAL(18,4)   NOT NULL,
    reason_detail   VARCHAR(255)    DEFAULT NULL,
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted         TINYINT         NOT NULL DEFAULT 0,
    version         INT             NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    UNIQUE KEY uk_wms_adj_line (adjust_id, line_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='库存调整单行';

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

CREATE TABLE wms_threshold_config (
    id                              BIGINT          NOT NULL AUTO_INCREMENT,
    tenant_id                       BIGINT          NOT NULL,
    inb_amount_threshold            DECIMAL(18,4)   NOT NULL DEFAULT 0,
    inb_qty_threshold               DECIMAL(18,4)   NOT NULL DEFAULT 0,
    out_amount_threshold            DECIMAL(18,4)   NOT NULL DEFAULT 0,
    out_qty_threshold               DECIMAL(18,4)   NOT NULL DEFAULT 0,
    adj_amount_threshold            DECIMAL(18,4)   NOT NULL DEFAULT 0,
    adj_qty_threshold               DECIMAL(18,4)   NOT NULL DEFAULT 0,
    approval_required_when_exceed   TINYINT         NOT NULL DEFAULT 1,
    created_at                      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted                         TINYINT         NOT NULL DEFAULT 0,
    version                         INT             NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    UNIQUE KEY uk_wms_threshold_tenant (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='WMS 审批阈值配置';