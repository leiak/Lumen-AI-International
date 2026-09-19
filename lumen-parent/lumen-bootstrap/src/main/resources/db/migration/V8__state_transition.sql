CREATE TABLE t_state_transition (
    id                  BIGINT          NOT NULL AUTO_INCREMENT,
    state_machine_code  VARCHAR(64)     NOT NULL,
    from_state          VARCHAR(32)     NOT NULL,
    to_state            VARCHAR(32)     NOT NULL,
    transition_name     VARCHAR(64)     NOT NULL,
    guard_expression    VARCHAR(255)    DEFAULT NULL,
    description         VARCHAR(255)    DEFAULT NULL,
    sort_order          INT             NOT NULL DEFAULT 0,
    tenant_id           BIGINT          NOT NULL DEFAULT 0,
    created_at          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted             TINYINT         NOT NULL DEFAULT 0,
    version             INT             NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    UNIQUE KEY uk_machine_from_to_tenant (state_machine_code, from_state, to_state, tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='表驱动状态机配置';
