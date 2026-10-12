-- =============================================================
-- V19: Dispatch schema (7 tables + CHECK constraints)
-- Iteration 4.1 PR1 — 配送调度 Vertical Slice
-- =============================================================

CREATE TABLE wms_vehicle (
    id              BIGINT          NOT NULL AUTO_INCREMENT,
    tenant_id       BIGINT          NOT NULL,
    plate_no        VARCHAR(32)     NOT NULL                COMMENT '车牌号',
    type            VARCHAR(32)     NOT NULL                COMMENT '车型: SMALL/MEDIUM/LARGE',
    max_load_kg     DECIMAL(18,3)   NOT NULL                COMMENT '最大载重(kg)',
    max_volume_m3   DECIMAL(18,3)   NOT NULL                COMMENT '最大体积(m3)',
    status          VARCHAR(32)     NOT NULL DEFAULT 'IDLE' COMMENT 'VEHICLE state: IDLE/DISPATCHED/LOADING/ENROUTE/RETURNING/OUT',
    driver_id       BIGINT          DEFAULT NULL            COMMENT '当前绑定司机(可空)',
    carrier_id      BIGINT          DEFAULT NULL            COMMENT '承运人(可空)',
    remark          VARCHAR(255)    DEFAULT NULL,
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted         TINYINT         NOT NULL DEFAULT 0,
    version         INT             NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    UNIQUE KEY uk_wms_vehicle_tenant_plate (tenant_id, plate_no, deleted),
    CONSTRAINT chk_wms_vehicle_load CHECK (max_load_kg > 0 AND max_volume_m3 > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='车辆主数据';

CREATE TABLE wms_driver (
    id              BIGINT          NOT NULL AUTO_INCREMENT,
    tenant_id       BIGINT          NOT NULL,
    name            VARCHAR(64)     NOT NULL,
    phone           VARCHAR(32)     NOT NULL,
    license_no      VARCHAR(64)     NOT NULL                COMMENT '驾驶证号',
    status          VARCHAR(32)     NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/SUSPENDED',
    remark          VARCHAR(255)    DEFAULT NULL,
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted         TINYINT         NOT NULL DEFAULT 0,
    version         INT             NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    UNIQUE KEY uk_wms_driver_tenant_phone (tenant_id, phone, deleted),
    CONSTRAINT chk_wms_driver_status CHECK (status IN ('ACTIVE','SUSPENDED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='司机主数据';

CREATE TABLE wms_carrier (
    id              BIGINT          NOT NULL AUTO_INCREMENT,
    tenant_id       BIGINT          NOT NULL,
    code            VARCHAR(32)     NOT NULL,
    name            VARCHAR(64)     NOT NULL,
    contact         VARCHAR(64)     DEFAULT NULL,
    status          VARCHAR(32)     NOT NULL DEFAULT 'ACTIVE',
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted         TINYINT         NOT NULL DEFAULT 0,
    version         INT             NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    UNIQUE KEY uk_wms_carrier_tenant_code (tenant_id, code, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='承运人主数据';

CREATE TABLE wms_delivery_order (
    id                  BIGINT          NOT NULL AUTO_INCREMENT,
    tenant_id           BIGINT          NOT NULL,
    source_no           VARCHAR(64)     NOT NULL                COMMENT '来源单号 (outbound.shipped -> outbound_no)',
    source_type         VARCHAR(32)     NOT NULL DEFAULT 'OUTBOUND' COMMENT 'OUTBOUND 等',
    no                  VARCHAR(32)     NOT NULL                COMMENT '配送单号',
    status              VARCHAR(32)     NOT NULL DEFAULT 'PENDING_DISPATCH' COMMENT 'DISPATCH_ORDER state',
    warehouse_id        BIGINT          NOT NULL                COMMENT '起点仓库',
    vehicle_id          BIGINT          DEFAULT NULL,
    driver_id           BIGINT          DEFAULT NULL,
    carrier_id          BIGINT          DEFAULT NULL,
    planned_depart_at   DATETIME        DEFAULT NULL,
    actual_depart_at    DATETIME        DEFAULT NULL,
    arrived_at          DATETIME        DEFAULT NULL,
    delivered_at        DATETIME        DEFAULT NULL,
    total_weight_kg     DECIMAL(18,3)   NOT NULL DEFAULT 0,
    total_volume_m3     DECIMAL(18,3)   NOT NULL DEFAULT 0,
    stop_count          INT             NOT NULL DEFAULT 0,
    sign_required       TINYINT         NOT NULL DEFAULT 1,
    sign_photo_url      VARCHAR(512)    DEFAULT NULL,
    exception_id        BIGINT          DEFAULT NULL            COMMENT '关联异常工单',
    remark              VARCHAR(500)    DEFAULT NULL,
    created_at          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted             TINYINT         NOT NULL DEFAULT 0,
    version             INT             NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    UNIQUE KEY uk_wms_delivery_order_tenant_no (tenant_id, no, deleted),
    KEY idx_wms_delivery_order_tenant_status (tenant_id, status, deleted),
    CONSTRAINT chk_wms_delivery_order_weight_kg  CHECK (total_weight_kg >= 0),
    CONSTRAINT chk_wms_delivery_order_volume_m3  CHECK (total_volume_m3 >= 0),
    CONSTRAINT chk_wms_delivery_order_stop_count CHECK (stop_count >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='配送订单';

CREATE TABLE wms_delivery_stop (
    id                  BIGINT          NOT NULL AUTO_INCREMENT,
    tenant_id           BIGINT          NOT NULL,
    order_id            BIGINT          NOT NULL,
    stop_no             INT             NOT NULL,
    address             VARCHAR(500)    NOT NULL,
    contact_name        VARCHAR(64)     DEFAULT NULL,
    contact_phone       VARCHAR(32)     DEFAULT NULL,
    latitude            DECIMAL(10,6)   DEFAULT NULL,
    longitude           DECIMAL(10,6)   DEFAULT NULL,
    planned_at          DATETIME        DEFAULT NULL,
    arrived_at          DATETIME        DEFAULT NULL,
    completed_at        DATETIME        DEFAULT NULL,
    status              VARCHAR(32)     NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/ARRIVED/COMPLETED/FAILED',
    failure_reason      VARCHAR(500)    DEFAULT NULL,
    created_at          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted             TINYINT         NOT NULL DEFAULT 0,
    version             INT             NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    KEY idx_wms_delivery_stop_order (tenant_id, order_id, stop_no, deleted),
    CONSTRAINT chk_wms_delivery_stop_no     CHECK (stop_no >= 1),
    CONSTRAINT chk_wms_delivery_stop_status CHECK (status IN ('PENDING','ARRIVED','COMPLETED','FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='配送站点';

CREATE TABLE wms_delivery_line (
    id              BIGINT          NOT NULL AUTO_INCREMENT,
    tenant_id       BIGINT          NOT NULL,
    order_id        BIGINT          NOT NULL,
    item_id         BIGINT          NOT NULL,
    qty             DECIMAL(18,3)   NOT NULL,
    weight_kg       DECIMAL(18,3)   NOT NULL DEFAULT 0,
    volume_m3       DECIMAL(18,3)   NOT NULL DEFAULT 0,
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted         TINYINT         NOT NULL DEFAULT 0,
    version         INT             NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    KEY idx_wms_delivery_line_order (tenant_id, order_id, deleted),
    CONSTRAINT chk_wms_delivery_line_qty        CHECK (qty > 0),
    CONSTRAINT chk_wms_delivery_line_weight_kg  CHECK (weight_kg >= 0),
    CONSTRAINT chk_wms_delivery_line_volume_m3  CHECK (volume_m3 >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='配送明细';

CREATE TABLE wms_delivery_exception (
    id              BIGINT          NOT NULL AUTO_INCREMENT,
    tenant_id       BIGINT          NOT NULL,
    order_id        BIGINT          NOT NULL,
    type            VARCHAR(32)     NOT NULL                COMMENT 'REJECTED/OVERTIME/DAMAGE/ADDR_ERR',
    status          VARCHAR(32)     NOT NULL DEFAULT 'OPEN'  COMMENT 'EXCEPTION state: OPEN/INVESTIGATING/RESOLVED/CLOSED/CANCELLED',
    description     VARCHAR(1000)   NOT NULL,
    handler         VARCHAR(64)     DEFAULT NULL            COMMENT '处理人',
    resolved_at     DATETIME        DEFAULT NULL,
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted         TINYINT         NOT NULL DEFAULT 0,
    version         INT             NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    KEY idx_wms_delivery_exception_order (tenant_id, order_id, deleted),
    CONSTRAINT chk_wms_delivery_exception_type CHECK (type IN ('REJECTED','OVERTIME','DAMAGE','ADDR_ERR'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='配送异常工单';