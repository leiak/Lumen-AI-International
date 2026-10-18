-- =============================================================
-- V33 — Iteration 4.5A: 客户门户 (B2B Self-Service) 身份与 RBAC
-- =============================================================
-- 背景: 4.5A 在 4.4 末态 (V25..V32) 基础上引入客户门户:
--   - 商家 (B2B 客户) 通过独立 SPA lumen-customer-web 自助查货/下单/对账
--   - 客户身份复用 sys_user (加 customer 角色) + 新 t_customer_user_link
--     (1 user ↔ N customer 多对多, 一会话一 active)
--   - 新 lumen-customer 模块: 薄门面, 调既有 dispatch/finance/wms service
--     (加重载, 强制 customerId 范围)
--   - JWT 加 aud claim (admin|portal), 拦截器按 path 路由
--
-- 内容:
--   1. sys_role             +1 行  (id 14 customer)
--   2. t_customer_user_link 建表
--   3. sys_permission       +7 行  (id 159..165 customer:*)
--   4. sys_role_permission  +7 行  (id 321..327 customer 角色绑权限)
--
-- 当前 id 占用 (V32 末态):
--   sys_permission        上限 158
--   sys_role              上限  13
--   sys_role_permission   上限 320
--   本次 +1 角色 / +7 权限 / +7 role-perm 后上限 = 14 / 165 / 327. 安全.
-- =============================================================

-- ------------------------------ 1. 客户角色 ------------------------------
INSERT INTO sys_role (id, tenant_id, code, name, description, status, version, deleted, created_at, updated_at)
VALUES (14, 1, 'customer', '客户 (B2B 商家)', '客户门户登录, 自助查货/下单/对账', 1, 0, 0, NOW(), NOW())
ON DUPLICATE KEY UPDATE
    name = VALUES(name),
    description = VALUES(description);

-- ------------------------------ 2. 客户-用户关联表 ------------------------------
CREATE TABLE t_customer_user_link (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  tenant_id     BIGINT       NOT NULL,
  user_id       BIGINT       NOT NULL COMMENT 'sys_user.id',
  customer_id   BIGINT       NOT NULL COMMENT 'wms_customer.id',
  is_primary    TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '主账号标志 (每个客户唯一 1 主) ',
  created_at    DATETIME(3)  NOT NULL,
  updated_at    DATETIME(3)  NOT NULL,
  deleted       TINYINT(1)   NOT NULL DEFAULT 0,
  version       INT          NOT NULL DEFAULT 1,
  PRIMARY KEY (id),
  UNIQUE KEY uk_link_user_customer (tenant_id, user_id, customer_id, deleted),
  KEY idx_link_user (tenant_id, user_id, deleted),
  KEY idx_link_customer (tenant_id, customer_id, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT 'B2B 客户与登录用户多对多绑定';

-- ------------------------------ 3. 客户权限 ------------------------------
INSERT INTO sys_permission (id, tenant_id, parent_id, code, type, name, path, sort_order, status, version, deleted, created_at, updated_at) VALUES
  (159, 1, NULL, 'customer:track:view',    'API', '客户查货',         '/portal/track',    1100, 1, 0, 0, NOW(), NOW()),
  (160, 1, NULL, 'customer:order:create',  'API', '客户下单',         '/portal/order',    1101, 1, 0, 0, NOW(), NOW()),
  (161, 1, NULL, 'customer:order:view',    'API', '客户查单',         '/portal/order',    1102, 1, 0, 0, NOW(), NOW()),
  (162, 1, NULL, 'customer:invoice:view',  'API', '客户看发票',       '/portal/invoice',  1103, 1, 0, 0, NOW(), NOW()),
  (163, 1, NULL, 'customer:recon:view',    'API', '客户看对账',       '/portal/recon',    1104, 1, 0, 0, NOW(), NOW()),
  (164, 1, NULL, 'customer:profile:view',  'API', '客户查自己资料',   '/portal/profile',  1105, 1, 0, 0, NOW(), NOW()),
  (165, 1, NULL, 'customer:profile:edit',  'API', '客户改自己资料',   '/portal/profile',  1106, 1, 0, 0, NOW(), NOW())
ON DUPLICATE KEY UPDATE
    name = VALUES(name),
    path = VALUES(path),
    sort_order = VALUES(sort_order);

-- ------------------------------ 4. 客户角色绑权限 ------------------------------
INSERT INTO sys_role_permission (id, role_id, permission_id, tenant_id, created_at) VALUES
  (321, 14, 159, 1, NOW()),
  (322, 14, 160, 1, NOW()),
  (323, 14, 161, 1, NOW()),
  (324, 14, 162, 1, NOW()),
  (325, 14, 163, 1, NOW()),
  (326, 14, 164, 1, NOW()),
  (327, 14, 165, 1, NOW())
ON DUPLICATE KEY UPDATE
    role_id = VALUES(role_id),
    permission_id = VALUES(permission_id);

-- =============================================================
-- 备注:
--   - 本迁移对 4 张表/schema 改动清单:
--       sys_role                 +1 行   (id 14 customer)
--       t_customer_user_link     建表   (8 列 + 1 唯一键 + 2 普通索引)
--       sys_permission           +7 行  (id 159..165 customer:*)
--       sys_role_permission      +7 行  (id 321..327 customer 角色绑权限)
--   - 所有 INSERT 都带 ON DUPLICATE KEY UPDATE, 4.5A 启动期可重跑幂等;
--     CREATE TABLE 不可重放, 但 Flyway 维护 schema_history, 二次启动
--     直接跳过 V33, 无 DDL 冲突.
--   - 本迁移不引入数据, 不动既有 V25..V32 任何行; sys_role / sys_permission /
--     sys_role_permission 仅在上限内追加新 id, 上限安全余量见上方 "当前 id 占用".
--   - 本迁移与 4.5A.1 后续 task (T1.2 JwtUtil audience / T1.3 JwtAuthFilter
--     routing) 无运行时依赖: 上述 task 只读 sys_role.code='customer' 与
--     customer:* 权限 code, 本迁移已就绪; 不需要再触 DB.
--   - t_customer_user_link 由 4.5A.2 PR2 (lumen-customer 模块) 引用,
--     唯一键 uk_link_user_customer (tenant_id, user_id, customer_id, deleted)
--     保证 link 不可重复, 多租户隔离天然成立.
--   - is_primary 字段为 4.5B 预留 (主账号标志, 每个客户唯一 1 主),
--     4.5A 阶段不消费, 仅随建表落地.
-- =============================================================
