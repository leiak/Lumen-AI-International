-- =============================================================
-- V42: 国际货运委托单域 (Iteration 4.6)
--
-- 背景:
--   仓库 README 定位是「面向货代、跨境电商、进出口企业的全链路国际物流业务平台」,
--   但 V42 之前的 11 个 Maven 模块里没有委托单域, 设计文档列的业务表大半还没落库
--   (V42 之前落库 58 张, 本文件再加 3 张 = 61 张), 门户的「下单」建的是
--   城配单 (wms_delivery_order) 而不是国际货运委托单。委托单是合同级主干单据,
--   向上承接询价报价, 向下驱动订舱/操作/报关/干线/仓储/结算/跟踪。
--
-- 本文件内容 (写完即冻结; 任何后续变更走新迁移 V43+):
--   1. 3 张新表: t_order / t_order_cargo / t_order_service_item
--   2. t_state_transition seed 15 行 (state_machine_code='ORDER', 9 态)
--   3. sys_permission seed 14 个 (10 管理端 order:* + 4 门户端 customer:freight_order:*)
--   4. sys_role seed 3 个 (id 15/16/17)
--   5. sys_role_permission seed 27 行
--
-- 编号: 复用 sys_number_rule 的 code='ORDER' (V4 已 seed: prefix='ORD',
--       date_format='yyyyMMdd', seq_length=6, reset_policy='DAY')。
--       NumberGenerator.generate("ORDER") 产出 ORD-20261110-000001 (prefix/period/序号之间各一个连字符)。
--       **本文件不再 seed 编号规则** —— 那条规则 V4 就种好了, 本轮只是第一次真正消费它。
--
-- 幂等/唯一键注意:
--   sys_permission 有 UNIQUE KEY uk_perm_tenant_code (tenant_id, code). 用新 id 重插已存在的
--   code 撞的是这个唯一键而不是主键 —— MySQL 会就地更新已存在的那一行, 新 id 永远不会被创建,
--   并且留下悬空的 sys_role_permission. V36 正是这么踩的, V37 事后擦的屁股。
--   本轮 14 个 code 全是新的, 但仍写 ON DUPLICATE KEY UPDATE (照 V33 / V40 的做法), 重复执行也安全。
--
--   反方向 (主键撞车) 也要防: 本轮显式指定了 id (权限 172..185 / 角色 15..17 / 绑定 334..360),
--   这些 id 必须**高于各表当前最大 id** —— 否则撞主键会直接报错, 而不是就地更新。
--   各表的 id 段选取依据写在下面每一节的注释里。
--
--   t_order 的业务单号唯一键刻意**不带 deleted**: uk_order_no (tenant_id, order_no)。
--   委托单号 ORD-yyyyMMdd-NNNNNN 是合同级单据, 一旦发出就永久占用, 软删后不得复用 ——
--   否则同一个单号会指向两笔不同的业务, 对账时无法区分。
--   这与 V15 的 WMS 单据口径一致 (uk_wms_inbound_no / uk_wms_out_no / uk_wms_adj_no
--   都是 (tenant_id, no), 不带 deleted)。
--   V19 的 uk_wms_delivery_order_tenant_no (tenant_id, no, deleted) 是另一口径 ——
--   城配单允许软删后复用单号, 委托单不允许, 故本轮不沿用。
--
-- Flyway checksum 提示:
--   本文件在迭代 4.6 期间改过 (唯一键去掉 deleted / guard_expression 换成真角色码)。
--   仓库自带的 dev 库 (lumen-bootstrap/src/main/docker/docker-compose.dev.yml 的
--   lumen_mysql_data 卷) 与 test 库 (international_mysql_data 卷) **都没应用过 V42** ——
--   V42 是本迭代才新建的, 两个卷的最后写入时间都早于它。
--   但如果哪个环境真的应用过早期版 V42, 改这个文件会导致 Flyway checksum 不匹配而**拒绝启动**,
--   而集成测试用一次性 testcontainers **永远发现不了这个问题**。
--   遇到这种情况: 重建该环境的库, 或对该库执行 `flyway repair`。
--
-- 状态机约定:
--   tenant_id = 0 表示全局行 —— MybatisPlusConfig.ignoreTable 显式豁免了 t_state_transition
--   的租户拦截 (V16 / V20 的状态机 seed 也全是 0)。填 1 会让其它租户看不到转换规则。
--   guard_expression 是 documentary 字段, **不求值** (V26 迁移注释写明);
--   沿用 V20 惯例写 'role:<code>' 字符串, 真正的守卫写在 service 代码里。
--   <code> 必须是 sys_role 里**真实存在**的 code —— 本轮用的是
--   order_sales / order_operator / order_supervisor / customer / finance_ops / finance_manager,
--   自造一个不存在的 code 等于写了句永远对不上的注释。
-- =============================================================

-- ------------------------------ 1. 三张新表 ------------------------------

CREATE TABLE t_order (
    id                    BIGINT          NOT NULL AUTO_INCREMENT,
    tenant_id             BIGINT          NOT NULL,
    order_no              VARCHAR(32)     NOT NULL,
    business_type         VARCHAR(32)     NOT NULL,
    customer_id           BIGINT          NOT NULL COMMENT 'wms_customer.id (无 FK, 与仓库既有做法一致)',
    customer_contract_id  BIGINT          DEFAULT NULL COMMENT '预留: 合同表未落库',
    sales_id              BIGINT          DEFAULT NULL COMMENT 'sys_user.id',
    operator_id           BIGINT          DEFAULT NULL COMMENT 'sys_user.id',
    trade_direction       VARCHAR(16)     NOT NULL,
    incoterms            VARCHAR(16)     NOT NULL,
    service_type          VARCHAR(16)     NOT NULL,
    origin_port_id        BIGINT          DEFAULT NULL COMMENT '预留: t_port 未落库',
    dest_port_id          BIGINT          DEFAULT NULL COMMENT '预留: t_port 未落库',
    origin_address        VARCHAR(255)    DEFAULT NULL,
    dest_address          VARCHAR(255)    DEFAULT NULL,
    pickup_warehouse_id   BIGINT          DEFAULT NULL,
    delivery_warehouse_id BIGINT          DEFAULT NULL,
    cargo_ready_date      DATE            DEFAULT NULL,
    etd                   DATE            DEFAULT NULL,
    eta                   DATE            DEFAULT NULL,
    total_qty             DECIMAL(18,3)   NOT NULL DEFAULT 0 COMMENT '由 t_order_cargo 聚合',
    total_weight_kg       DECIMAL(18,3)   NOT NULL DEFAULT 0 COMMENT '由 t_order_cargo 聚合',
    total_volume_cbm      DECIMAL(18,3)   NOT NULL DEFAULT 0 COMMENT '由 t_order_cargo 聚合',
    is_dangerous          TINYINT         NOT NULL DEFAULT 0 COMMENT '由明细派生: EXISTS(明细.is_dangerous=1)',
    is_oversized          TINYINT         NOT NULL DEFAULT 0 COMMENT '独立录入, 不派生',
    temperature_range     VARCHAR(32)     DEFAULT NULL,
    container_qty         INT             DEFAULT NULL,
    container_type_code   VARCHAR(16)     DEFAULT NULL,
    marks_and_nos         VARCHAR(500)    DEFAULT NULL,
    cargo_description     VARCHAR(500)    DEFAULT NULL,
    order_status          VARCHAR(32)     NOT NULL DEFAULT 'DRAFT',
    settlement_currency   CHAR(3)         NOT NULL DEFAULT 'CNY',
    exchange_rate_type    VARCHAR(16)     DEFAULT NULL COMMENT 'SPOT/CONTRACT/LOCKED, 本轮只落字段',
    locked_exchange_rate  DECIMAL(18,6)   DEFAULT NULL,
    is_locked             TINYINT         NOT NULL DEFAULT 0,
    lock_at               DATETIME        DEFAULT NULL,
    lock_by               BIGINT          DEFAULT NULL,
    inquiry_id            BIGINT          DEFAULT NULL COMMENT '预留: 询价表未落库',
    quotation_id          BIGINT          DEFAULT NULL COMMENT '预留: 报价表未落库',
    cancel_reason         VARCHAR(255)    DEFAULT NULL,
    close_reason          VARCHAR(255)    DEFAULT NULL,
    void_reason           VARCHAR(255)    DEFAULT NULL,
    submitted_at          DATETIME        DEFAULT NULL,
    confirmed_at          DATETIME        DEFAULT NULL,
    delivered_at          DATETIME        DEFAULT NULL,
    settled_at            DATETIME        DEFAULT NULL,
    closed_at             DATETIME        DEFAULT NULL,
    remark                VARCHAR(1000)   DEFAULT NULL,
    created_by            BIGINT          DEFAULT NULL,
    created_at            DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by            BIGINT          DEFAULT NULL,
    updated_at            DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted               TINYINT         NOT NULL DEFAULT 0,
    version               INT             NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_no (tenant_id, order_no),
    KEY idx_order_customer (tenant_id, customer_id, order_status),
    KEY idx_order_status (tenant_id, order_status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='货运委托单';

CREATE TABLE t_order_cargo (
    id              BIGINT          NOT NULL AUTO_INCREMENT,
    tenant_id       BIGINT          NOT NULL,
    order_id        BIGINT          NOT NULL,
    cargo_name_cn   VARCHAR(128)    DEFAULT NULL,
    cargo_name_en   VARCHAR(128)    DEFAULT NULL,
    hs_code         VARCHAR(16)     DEFAULT NULL,
    pkg_type        VARCHAR(32)     DEFAULT NULL,
    pkg_qty         DECIMAL(18,3)   NOT NULL DEFAULT 0,
    unit            VARCHAR(16)     DEFAULT NULL,
    weight_kg       DECIMAL(18,3)   NOT NULL DEFAULT 0,
    net_weight_kg   DECIMAL(18,3)   DEFAULT NULL,
    volume_cbm      DECIMAL(18,3)   NOT NULL DEFAULT 0,
    length_cm       DECIMAL(18,3)   DEFAULT NULL,
    width_cm        DECIMAL(18,3)   DEFAULT NULL,
    height_cm       DECIMAL(18,3)   DEFAULT NULL,
    is_stackable    TINYINT         NOT NULL DEFAULT 1,
    cargo_value     DECIMAL(18,2)   DEFAULT NULL,
    currency_code   CHAR(3)         DEFAULT NULL,
    brand_name      VARCHAR(128)    DEFAULT NULL,
    model           VARCHAR(128)    DEFAULT NULL,
    is_dangerous    TINYINT         NOT NULL DEFAULT 0,
    un_no           VARCHAR(16)     DEFAULT NULL,
    danger_class    VARCHAR(16)     DEFAULT NULL,
    remark          VARCHAR(500)    DEFAULT NULL,
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted         TINYINT         NOT NULL DEFAULT 0,
    version         INT             NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    KEY idx_cargo_order (tenant_id, order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='委托单货物明细';

CREATE TABLE t_order_service_item (
    id            BIGINT          NOT NULL AUTO_INCREMENT,
    tenant_id     BIGINT          NOT NULL,
    order_id      BIGINT          NOT NULL,
    fee_type      VARCHAR(32)     NOT NULL,
    fee_name      VARCHAR(128)    NOT NULL,
    fee_category  VARCHAR(32)     DEFAULT NULL,
    calc_basis    VARCHAR(16)     NOT NULL,
    qty           DECIMAL(18,3)   NOT NULL DEFAULT 0,
    unit_price    DECIMAL(18,4)   NOT NULL DEFAULT 0,
    amount        DECIMAL(18,2)   NOT NULL DEFAULT 0,
    currency_code CHAR(3)         NOT NULL DEFAULT 'CNY',
    supplier_id   BIGINT          DEFAULT NULL,
    cost_amount   DECIMAL(18,2)   DEFAULT NULL,
    locked_amount DECIMAL(18,2)   DEFAULT NULL,
    is_locked     TINYINT         NOT NULL DEFAULT 0,
    is_mandatory  TINYINT         NOT NULL DEFAULT 0,
    source        VARCHAR(16)     NOT NULL DEFAULT 'MANUAL' COMMENT 'MANUAL / QUOTATION(预留)',
    remark        VARCHAR(500)    DEFAULT NULL,
    created_at    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted       TINYINT         NOT NULL DEFAULT 0,
    version       INT             NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    KEY idx_service_order (tenant_id, order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='委托单服务项与费用预算';

-- ------------------------------ 2. ORDER 状态机 seed (15 行) ------------------------------
-- 9 态: DRAFT / PENDING_CONFIRM / CONFIRMED / IN_OPERATION / DELIVERED / SETTLED / CLOSED / CANCELLED / VOID
-- 裁剪说明: 设计文档的 15 态里的 BOOKED / SHIPPED / IN_TRANSIT / ARRIVED_PORT / CLEARING / DELIVERING
--           6 态的驱动方 (订舱 / 跟踪 / 报关模块) 都还没落库, 本轮不 seed —— 否则 allowedNext()
--           会向调用方暴露一个走不通的死边。等对应切片落地时追加 seed 行即可 (配置表, 不改代码)。
-- 幂等: t_state_transition 有 UNIQUE KEY uk_machine_from_to_tenant
--       (state_machine_code, from_state, to_state, tenant_id)。本 INSERT **不指定 id**
--       (让 MySQL 自增), 重复执行时撞的是这个唯一键, 故补 ON DUPLICATE KEY UPDATE ——
--       就地更新 guard_expression / description, 不会插出第二行同一条转换。

INSERT INTO t_state_transition
    (state_machine_code, from_state, to_state, transition_name, guard_expression, description, sort_order, tenant_id, created_at, updated_at, deleted, version)
VALUES
('ORDER', 'DRAFT',          'PENDING_CONFIRM', 'submit',         'role:order_sales',      '提交委托单待客户确认', 10, 0, NOW(), NOW(), 0, 1),
('ORDER', 'DRAFT',          'CANCELLED',       'cancel',         'role:order_sales',      '草稿阶段取消',         11, 0, NOW(), NOW(), 0, 1),
('ORDER', 'DRAFT',          'VOID',            'void',           'role:order_supervisor', '草稿作废',             12, 0, NOW(), NOW(), 0, 1),
('ORDER', 'PENDING_CONFIRM','CONFIRMED',       'confirm',        'role:customer',         '客户确认委托单',       20, 0, NOW(), NOW(), 0, 1),
('ORDER', 'PENDING_CONFIRM','DRAFT',           'return',         'role:order_sales',      '退回销售要求修改',     21, 0, NOW(), NOW(), 0, 1),
('ORDER', 'PENDING_CONFIRM','CANCELLED',       'cancel',         'role:order_sales',      '待确认阶段取消',       22, 0, NOW(), NOW(), 0, 1),
('ORDER', 'PENDING_CONFIRM','VOID',            'void',           'role:order_supervisor', '超时未确认作废',       23, 0, NOW(), NOW(), 0, 1),
('ORDER', 'CONFIRMED',      'IN_OPERATION',    'start_operation','role:order_operator',   '开始操作执行',         30, 0, NOW(), NOW(), 0, 1),
('ORDER', 'CONFIRMED',      'CANCELLED',       'cancel',         'role:order_supervisor', '已确认后撤单',         31, 0, NOW(), NOW(), 0, 1),
('ORDER', 'CONFIRMED',      'VOID',            'void',           'role:order_supervisor', '已确认后作废',         32, 0, NOW(), NOW(), 0, 1),
('ORDER', 'IN_OPERATION',   'DELIVERED',       'deliver',        'role:order_operator',   '操作完成交付',         40, 0, NOW(), NOW(), 0, 1),
('ORDER', 'IN_OPERATION',   'CANCELLED',       'cancel',         'role:order_supervisor', '操作中撤单',           41, 0, NOW(), NOW(), 0, 1),
('ORDER', 'IN_OPERATION',   'VOID',            'void',           'role:order_supervisor', '操作中作废',           42, 0, NOW(), NOW(), 0, 1),
('ORDER', 'DELIVERED',      'SETTLED',         'settle',         'role:finance_ops',      '费用闭环结算',         50, 0, NOW(), NOW(), 0, 1),
('ORDER', 'SETTLED',        'CLOSED',          'close',          'role:finance_manager',  '关账锁定归档',         60, 0, NOW(), NOW(), 0, 1)
ON DUPLICATE KEY UPDATE
    transition_name = VALUES(transition_name),
    guard_expression = VALUES(guard_expression),
    description = VALUES(description),
    sort_order = VALUES(sort_order);

-- ------------------------------ 3. 14 个权限码 ------------------------------
-- id 段依据: sys_permission 当前最大 id = 171 (V40), 故从 172 起。
-- sort_order: 管理端段已占用 901..905 与 1001..1010 (finance), 故 order 段取 1020..1029;
--             门户端段已占用 1100..1112 (V33/V36/V40), 故取 1113..1116。

INSERT INTO sys_permission (id, tenant_id, parent_id, code, type, name, path, sort_order, status, version, deleted, created_at, updated_at) VALUES
  (172, 1, NULL, 'order:view',                    'API', '查看委托单',         '/orders', 1020, 1, 0, 0, NOW(), NOW()),
  (173, 1, NULL, 'order:create',                  'API', '创建委托单',         '/orders', 1021, 1, 0, 0, NOW(), NOW()),
  (174, 1, NULL, 'order:edit',                    'API', '编辑委托单',         '/orders', 1022, 1, 0, 0, NOW(), NOW()),
  (175, 1, NULL, 'order:submit',                  'API', '提交/退回委托单',    '/orders', 1023, 1, 0, 0, NOW(), NOW()),
  (176, 1, NULL, 'order:confirm',                 'API', '确认委托单',         '/orders', 1024, 1, 0, 0, NOW(), NOW()),
  (177, 1, NULL, 'order:cancel',                  'API', '取消委托单',         '/orders', 1025, 1, 0, 0, NOW(), NOW()),
  (178, 1, NULL, 'order:void',                    'API', '作废委托单',         '/orders', 1026, 1, 0, 0, NOW(), NOW()),
  (179, 1, NULL, 'order:operate',                 'API', '操作/交付委托单',    '/orders', 1027, 1, 0, 0, NOW(), NOW()),
  (180, 1, NULL, 'order:settle',                  'API', '结算委托单',         '/orders', 1028, 1, 0, 0, NOW(), NOW()),
  (181, 1, NULL, 'order:close',                   'API', '关账委托单',         '/orders', 1029, 1, 0, 0, NOW(), NOW()),
  (182, 1, NULL, 'customer:freight_order:view',   'API', '客户看自己的货运委托单', '/portal/freight-order', 1113, 1, 0, 0, NOW(), NOW()),
  (183, 1, NULL, 'customer:freight_order:create', 'API', '客户创建货运委托单',     '/portal/freight-order', 1114, 1, 0, 0, NOW(), NOW()),
  (184, 1, NULL, 'customer:freight_order:confirm','API', '客户确认货运委托单',     '/portal/freight-order', 1115, 1, 0, 0, NOW(), NOW()),
  (185, 1, NULL, 'customer:freight_order:cancel', 'API', '客户取消货运委托单',     '/portal/freight-order', 1116, 1, 0, 0, NOW(), NOW())
ON DUPLICATE KEY UPDATE
    name = VALUES(name),
    path = VALUES(path),
    sort_order = VALUES(sort_order);

-- ------------------------------ 4. 3 个新角色 ------------------------------
-- id 段依据: sys_role 当前最大 id = 14 (customer, V33), 故从 15 起。
-- 既有: 11=finance_ops 12=finance_reviewer (V26) 13=finance_manager (V30) 14=customer (V33)

INSERT INTO sys_role (id, tenant_id, code, name, description, status, version, deleted, created_at, updated_at) VALUES
  (15, 1, 'order_sales',      '货运委托单销售', '委托单建单 / 编辑 / 提交 / 确认 / 取消', 1, 0, 0, NOW(), NOW()),
  (16, 1, 'order_operator',   '货运委托单操作', '委托单查看 / 操作推进 / 交付',           1, 0, 0, NOW(), NOW()),
  (17, 1, 'order_supervisor', '货运委托单主管', '委托单查看 / 操作 / 作废',             1, 0, 0, NOW(), NOW())
ON DUPLICATE KEY UPDATE
    name = VALUES(name),
    description = VALUES(description);

-- ------------------------------ 5. 27 行 sys_role_permission ------------------------------
-- id 段依据: sys_role_permission 当前最大 id = 333 (V40), 故从 334 起。
-- **SUPER_ADMIN (role 1) 必须显式绑定** —— sys_role_permission 不是「SUPER_ADMIN 自动拥有全部权限」,
-- V2 只给 role 1 绑了 permission 1..27, 后续每批新权限都是显式补行 (V17 / V21 都是这个做法)。
-- 不绑的话 admin 用户登录后拿不到 order:*, 管理端页面直接 403。
--
-- 门户端权限还有**第二条通路**: PortalAuthService.CUSTOMER_PERMS 是硬编码 List,
-- 只 seed sys_permission / sys_role_permission 门户仍然 403。Task 7 会改那个常量。

INSERT INTO sys_role_permission (id, role_id, permission_id, tenant_id, created_at) VALUES
  -- SUPER_ADMIN: 全部 10 个管理端权限 (334..343)
  (334,  1, 172, 1, NOW()),
  (335,  1, 173, 1, NOW()),
  (336,  1, 174, 1, NOW()),
  (337,  1, 175, 1, NOW()),
  (338,  1, 176, 1, NOW()),
  (339,  1, 177, 1, NOW()),
  (340,  1, 178, 1, NOW()),
  (341,  1, 179, 1, NOW()),
  (342,  1, 180, 1, NOW()),
  (343,  1, 181, 1, NOW()),
  -- order_sales (15): view/create/edit/submit/confirm/cancel (344..349)
  (344, 15, 172, 1, NOW()),
  (345, 15, 173, 1, NOW()),
  (346, 15, 174, 1, NOW()),
  (347, 15, 175, 1, NOW()),
  (348, 15, 176, 1, NOW()),
  (349, 15, 177, 1, NOW()),
  -- order_operator (16): view/operate (350..351)
  (350, 16, 172, 1, NOW()),
  (351, 16, 179, 1, NOW()),
  -- order_supervisor (17): view/void/operate (352..354)
  (352, 17, 172, 1, NOW()),
  (353, 17, 178, 1, NOW()),
  (354, 17, 179, 1, NOW()),
  -- finance_ops (11) + order:settle (355) / finance_manager (13) + order:close (356)
  (355, 11, 180, 1, NOW()),
  (356, 13, 181, 1, NOW()),
  -- customer (14): 4 个门户端权限 (357..360)
  (357, 14, 182, 1, NOW()),
  (358, 14, 183, 1, NOW()),
  (359, 14, 184, 1, NOW()),
  (360, 14, 185, 1, NOW())
ON DUPLICATE KEY UPDATE
    role_id = VALUES(role_id),
    permission_id = VALUES(permission_id);
