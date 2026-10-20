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
--     block 5 (Task 2.5): sys_dict delivery_order_source_type.PORTAL 新枚举值
--   (block 4 SM 扩展原计划已删: V20 line 38 已存 (DISPATCH_ORDER, PENDING_DISPATCH, CANCELLED, tenant=0)
--    唯一行, V8 UNIQUE KEY (state_machine_code, from_state, to_state, tenant_id) 阻止新增.
--    customer cancel 授权改在 SERVICE 层 (@PreAuthorize('customer:order:cancel') + ownership check).)
--   Flyway 不允许同一版本号拆成多文件, 故一个 V36 + 多 block.
--   本 PR (4.5B.1 + 4.5B.2) 已写 block 1+2+3+5 (block 4 删除, 见 T1.3 fix).
-- =============================================================

ALTER TABLE wms_delivery_order
    ADD COLUMN planned_pickup_window_start DATETIME NULL
        COMMENT '商家选取件窗口起点 (>= now+2h, < now+30d)' AFTER planned_depart_at,
    ADD COLUMN planned_pickup_window_end   DATETIME NULL
        COMMENT '商家选取件窗口终点 (> start, <= start+8h, 同日)' AFTER planned_pickup_window_start,
    ADD INDEX idx_do_pickup_window (tenant_id, planned_pickup_window_start);

-- 2. t_customer_rate_override (Task 1.2 块)
CREATE TABLE t_customer_rate_override (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id       BIGINT       NOT NULL,
    customer_id     BIGINT       NOT NULL,
    business_type   VARCHAR(32)  NOT NULL COMMENT 'LAST_MILE / WAREHOUSING / etc, 同 billing_rule.business_type',
    base_amount     DECIMAL(18,4) NOT NULL COMMENT '固定基础价 (含税)',
    currency        VARCHAR(8)   NOT NULL COMMENT 'ISO 4217, 默认 CNY',
    effective_from  DATE         NOT NULL,
    effective_to    DATE         NULL    COMMENT 'NULL = 长期有效',
    remark          VARCHAR(255) NULL,
    deleted         TINYINT      NOT NULL DEFAULT 0,
    created_at      DATETIME     NOT NULL,
    updated_at      DATETIME     NOT NULL,
    version         INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_tenant_customer_biz (tenant_id, customer_id, business_type, effective_from),
    KEY idx_override_active (tenant_id, customer_id, business_type, effective_from, effective_to, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
    COMMENT='商家合同价 override (优先于 BillingRule 跑 Aviator 求值)';

-- 3. RBAC: 3 个客户权限 (V33 已加 customer 角色 id 14, 此处加权限并绑角色)
-- sys_permission 当前 id 上限 = 165 (V33 末态), 本次 +3 → 上限 = 168
INSERT INTO sys_permission (id, tenant_id, parent_id, code, type, name, path, sort_order, status, version, deleted, created_at, updated_at)
VALUES
    (166, 1, NULL, 'customer:order:create', 'API', '客户下单 (含估价)',  '/portal/order',     1107, 1, 0, 0, NOW(), NOW()),
    (167, 1, NULL, 'customer:order:view',   'API', '客户查单 (列表+详情)', '/portal/order',    1108, 1, 0, 0, NOW(), NOW()),
    (168, 1, NULL, 'customer:order:cancel', 'API', '客户取消 PENDING 单',   '/portal/order',    1109, 1, 0, 0, NOW(), NOW())
ON DUPLICATE KEY UPDATE
    name = VALUES(name),
    path = VALUES(path),
    sort_order = VALUES(sort_order);

-- 绑到 customer 角色 (id 14, V33)
-- sys_role_permission 当前 id 上限 = 327 (V33 末态), 本次 +3 → 上限 = 330
INSERT INTO sys_role_permission (id, role_id, permission_id, tenant_id, created_at) VALUES
    (328, 14, 166, 1, NOW()),
    (329, 14, 167, 1, NOW()),
    (330, 14, 168, 1, NOW())
ON DUPLICATE KEY UPDATE
    role_id = VALUES(role_id),
    permission_id = VALUES(permission_id);

-- (原 block 4 SM 扩展 已删除: V20 已存 (DISPATCH_ORDER, PENDING_DISPATCH, CANCELLED, tenant=0) 唯一行, V8 UNIQUE KEY 阻止新增.
--  customer cancel 的授权在 SERVICE 层强制 (RBAC perm 168 + ownership check in DeliveryOrderService.cancelForCustomer).)

-- =============================================================
-- Block 5 (Task 2.5): sys_dict + sys_dict_item for delivery_order_source_type.PORTAL
-- =============================================================
-- 背景:
--   T2.3 createForCustomer / T2.4 cancelForCustomer 写 order.source_type = 'PORTAL'
--   (与 V19 默认 OUTBOUND 并列), 但 sys_dict (masterdata) 中配送单来源字典目前为空,
--   字典下拉/校验组件无法识别 PORTAL. 此处补登, 沿用 sys_dict / sys_dict_item
--   的 schema (见 V3):
--     sys_dict       : id, tenant_id, code, name, description, created_at, updated_at, deleted, version
--     sys_dict_item  : id, tenant_id, dict_id, code, label, sort_order, status, created_at, updated_at, deleted, version
--   唯一约束: sys_dict_item(tenant_id, dict_id, code) — (0, 2001, 'PORTAL') 在 4.5B.2 范围内唯一.
--   id 取 2001 是避开历史 sys_dict / sys_dict_item (V3 之后无任何 INSERT) 全空场景下
--   的安全高值; tenant_id = 0 表示全局行 (与 V20 dispatch state-machine 约定一致).
--   上游 dispatch 创建路径 setSourceType("OUTBOUND") / "PORTAL" 仍在 service 层
--   setStatus-style 直接写, 这里只补登字典供前端/校验组件查询.

-- 5.1 字典分类行 (delivery_order_source_type) — 若已有则刷新 name/description
INSERT INTO sys_dict (id, tenant_id, code, name, description, created_at, updated_at, deleted, version)
VALUES
    (2001, 0, 'delivery_order_source_type', '配送单来源',
     '配送单来源类型枚举. OUTBOUND: 出库单触发 (V19 默认); PORTAL: 客户门户自助下单 (4.5B.2).',
     NOW(), NOW(), 0, 0)
ON DUPLICATE KEY UPDATE
    name = VALUES(name),
    description = VALUES(description);

-- 5.2 字典项行 (PORTAL) — 4.5B.2 新增; sort_order=30 排在 OUTBOUND 等已有值之后
-- 注: sys_dict_item 目前无任何 INSERT (V3 之后无 seed), 此处直接插入 PORTAL 单项;
-- 如未来要补 OUTBOUND/API/IMPORT 等值, 走同模式追加同 dict_id=2001 的 INSERT.
INSERT INTO sys_dict_item (id, tenant_id, dict_id, code, label, sort_order, status, created_at, updated_at, deleted, version)
VALUES
    (2001, 0, 2001, 'PORTAL', '客户门户下单', 30, 1, NOW(), NOW(), 0, 0)
ON DUPLICATE KEY UPDATE
    label = VALUES(label),
    sort_order = VALUES(sort_order),
    status = VALUES(status);
