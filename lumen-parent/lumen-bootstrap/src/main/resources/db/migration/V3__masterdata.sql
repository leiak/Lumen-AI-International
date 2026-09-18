-- ============================================================
-- V3__masterdata.sql
-- Purpose:  主数据表（字典 + 国家 + 币种 + 计量单位）+ 演示数据
-- Tables:   sys_dict, sys_dict_item, sys_country, sys_currency, sys_uom
-- Notes:    字典/规则/序列等共享主数据表在 MybatisPlusConfig 中被
--           `ignoreTable` 显式豁免租户过滤；其余表继承 BaseEntity
--           列对齐实体的 id / tenant_id / created_at / updated_at /
--           deleted / version。
-- ============================================================

-- ----------------------------------------------------------------
-- 字典（分类）
-- 来源: lumen-masterdata/src/main/java/com/lumen/masterdata/entity/SysDict.java
-- ----------------------------------------------------------------
CREATE TABLE sys_dict (
  id          BIGINT       NOT NULL COMMENT '主键',
  tenant_id   BIGINT       DEFAULT NULL COMMENT '租户 ID',
  code        VARCHAR(64)  DEFAULT NULL COMMENT '字典编码',
  name        VARCHAR(128) DEFAULT NULL COMMENT '字典名称',
  description VARCHAR(256) DEFAULT NULL COMMENT '描述',
  created_at  DATETIME     DEFAULT NULL COMMENT '创建时间',
  updated_at  DATETIME     DEFAULT NULL COMMENT '更新时间',
  deleted     INT          DEFAULT 0 COMMENT '逻辑删除: 0=正常 1=删除',
  version     INT          DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (id),
  UNIQUE KEY uk_dict_tenant_code (tenant_id, code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='数据字典';

CREATE INDEX idx_dict_tenant_deleted ON sys_dict(tenant_id, deleted);

-- ----------------------------------------------------------------
-- 字典项
-- 来源: lumen-masterdata/src/main/java/com/lumen/masterdata/entity/SysDictItem.java
-- ----------------------------------------------------------------
CREATE TABLE sys_dict_item (
  id          BIGINT       NOT NULL COMMENT '主键',
  tenant_id   BIGINT       DEFAULT NULL COMMENT '租户 ID',
  dict_id     BIGINT       DEFAULT NULL COMMENT '所属字典 ID',
  code        VARCHAR(64)  DEFAULT NULL COMMENT '项编码',
  label       VARCHAR(128) DEFAULT NULL COMMENT '项显示文本',
  sort_order  INT          DEFAULT NULL COMMENT '排序',
  status      INT          DEFAULT NULL COMMENT '状态: 1=启用 0=禁用',
  created_at  DATETIME     DEFAULT NULL COMMENT '创建时间',
  updated_at  DATETIME     DEFAULT NULL COMMENT '更新时间',
  deleted     INT          DEFAULT 0 COMMENT '逻辑删除: 0=正常 1=删除',
  version     INT          DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (id),
  UNIQUE KEY uk_dict_item (tenant_id, dict_id, code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='字典项';

CREATE INDEX idx_dict_item_tenant_deleted ON sys_dict_item(tenant_id, deleted);
CREATE INDEX idx_dict_item_dict           ON sys_dict_item(dict_id);

-- ----------------------------------------------------------------
-- 国家（ISO 3166-1 alpha-2）
-- 来源: lumen-masterdata/src/main/java/com/lumen/masterdata/entity/SysCountry.java
-- ----------------------------------------------------------------
CREATE TABLE sys_country (
  id          BIGINT       NOT NULL COMMENT '主键',
  tenant_id   BIGINT       DEFAULT NULL COMMENT '租户 ID',
  code        VARCHAR(8)   DEFAULT NULL COMMENT 'ISO 国家代码 (alpha-2)',
  name_cn     VARCHAR(64)  DEFAULT NULL COMMENT '中文名',
  name_en     VARCHAR(64)  DEFAULT NULL COMMENT '英文名',
  created_at  DATETIME     DEFAULT NULL COMMENT '创建时间',
  updated_at  DATETIME     DEFAULT NULL COMMENT '更新时间',
  deleted     INT          DEFAULT 0 COMMENT '逻辑删除: 0=正常 1=删除',
  version     INT          DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (id),
  UNIQUE KEY uk_country_tenant_code (tenant_id, code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='国家';

CREATE INDEX idx_country_tenant_deleted ON sys_country(tenant_id, deleted);

-- ----------------------------------------------------------------
-- 币种
-- 来源: lumen-masterdata/src/main/java/com/lumen/masterdata/entity/SysCurrency.java
-- ----------------------------------------------------------------
CREATE TABLE sys_currency (
  id          BIGINT       NOT NULL COMMENT '主键',
  tenant_id   BIGINT       DEFAULT NULL COMMENT '租户 ID',
  code        VARCHAR(8)   DEFAULT NULL COMMENT 'ISO 4217 币种代码',
  name        VARCHAR(64)  DEFAULT NULL COMMENT '币种名称',
  scale       INT          DEFAULT NULL COMMENT '小数位数',
  created_at  DATETIME     DEFAULT NULL COMMENT '创建时间',
  updated_at  DATETIME     DEFAULT NULL COMMENT '更新时间',
  deleted     INT          DEFAULT 0 COMMENT '逻辑删除: 0=正常 1=删除',
  version     INT          DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (id),
  UNIQUE KEY uk_currency_tenant_code (tenant_id, code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='币种';

CREATE INDEX idx_currency_tenant_deleted ON sys_currency(tenant_id, deleted);

-- ----------------------------------------------------------------
-- 计量单位
-- 来源: lumen-masterdata/src/main/java/com/lumen/masterdata/entity/SysUom.java
-- ----------------------------------------------------------------
CREATE TABLE sys_uom (
  id          BIGINT       NOT NULL COMMENT '主键',
  tenant_id   BIGINT       DEFAULT NULL COMMENT '租户 ID',
  code        VARCHAR(16)  DEFAULT NULL COMMENT '单位编码 (KG/CBM/CTN)',
  name        VARCHAR(64)  DEFAULT NULL COMMENT '单位名称',
  dimension   VARCHAR(16)  DEFAULT NULL COMMENT '维度: WEIGHT/VOLUME/QUANTITY',
  created_at  DATETIME     DEFAULT NULL COMMENT '创建时间',
  updated_at  DATETIME     DEFAULT NULL COMMENT '更新时间',
  deleted     INT          DEFAULT 0 COMMENT '逻辑删除: 0=正常 1=删除',
  version     INT          DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (id),
  UNIQUE KEY uk_uom_tenant_code (tenant_id, code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='计量单位';

CREATE INDEX idx_uom_tenant_deleted ON sys_uom(tenant_id, deleted);

-- ----------------------------------------------------------------
-- 演示数据：默认租户 (id=1) 下两份国家/币种/单位
-- 后续业务（如订单）会用 code 关联，所以种子保留。
-- ----------------------------------------------------------------
INSERT INTO sys_country (id, tenant_id, code, name_cn, name_en) VALUES
  (1, 1, 'CN', '中国',     'China'),
  (2, 1, 'US', '美国',     'United States');

INSERT INTO sys_currency (id, tenant_id, code, name, scale) VALUES
  (1, 1, 'CNY', '人民币', 2),
  (2, 1, 'USD', '美元',   2);

INSERT INTO sys_uom (id, tenant_id, code, name, dimension) VALUES
  (1, 1, 'KG',  '千克',   'WEIGHT'),
  (2, 1, 'CBM', '立方米', 'VOLUME');