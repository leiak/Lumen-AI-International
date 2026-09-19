# 核心 DDL 落地（SQL 层）v2.1

> 承接 `02 v2.1` 表结构，给出 **SQL 落地约定 + 关键表 DDL**（新表 + 全部业务单号唯一索引 + 幂等键索引 + 依赖外键）。
> 目的：把"业务字段"下沉为可执行的物理模型，并保证**幂等、唯一、审计、扩展**落地。
> 范围：DB=MySQL 8（InnoDB / utf8mb4），兼容 MySQL、TiDB、兼容 MySQL 的云库。

---

## 一、全局 SQL 约定

| 约定 | 说明 |
| --- | --- |
| 主键 | `id`=BIGINT UNSIGNED AUTO_INCREMENT |
| 多租户 | 核心业务表均含 `org_id BIGINT NOT NULL`（本版以 `org_id` 预留，租户字段列在第五章） |
| 审计字段 | 统一 `created_by/created_at/updated_by/updated_at`（BIGINT/DATETIME 默认 CURRENT_TIMESTAMP） |
| 逻辑删除 | `is_deleted TINYINT DEFAULT 0` + `deleted_at`（软删，留痕） |
| 业务单号 | 每张有业务号的表建**唯一索引**（`uk_<表>_<no>`） |
| 去重/幂等 | 事件与写入用 `request_id`/`event_id` 建**唯一索引**实现幂等 |
| 版本/扩展 | 主数据与规则带 `version`、校验版；个性化用 `t_extension_field`（通用 JSON） |
| 软引用一致性 | 关系用逻辑外键（业务字段），关键关系加**物理外键可选**（本版建议逻辑外键，索引必建） |

### 建表通用尾列（每张业务表复用）

```sql
`org_id`           BIGINT UNSIGNED NOT NULL COMMENT '租户/组织ID',
`created_by`       BIGINT UNSIGNED NOT NULL DEFAULT 0,
`created_at`       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
`updated_by`       BIGINT UNSIGNED NOT NULL DEFAULT 0,
`updated_at`       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
`is_deleted`       TINYINT NOT NULL DEFAULT 0,
`deleted_at`       DATETIME NULL,
PRIMARY KEY (`id`),
KEY `idx_org_created` (`org_id`,`created_at`)
```

---

## 二、关键表 DDL（新增表）

### 2.1 `t_event_outbox`（事件出口，幂等核心）

```sql
CREATE TABLE t_event_outbox (
  `id`            BIGINT UNSIGNED AUTO_INCREMENT,
  `event_id`      VARCHAR(64)  NOT NULL COMMENT '全局唯一事件ID',
  `event_type`    VARCHAR(64)  NOT NULL,
  `payload`       JSON         DEFAULT NULL,
  `payload_version` INT        NOT NULL DEFAULT 1,
  `entity_type`   VARCHAR(32)  NOT NULL,
  `entity_id`     BIGINT UNSIGNED NOT NULL,
  `request_id`    VARCHAR(64)  NOT NULL COMMENT '幂等键',
  `status`        ENUM('PENDING','PUBLISHED','FAILED') NOT NULL DEFAULT 'PENDING',
  `retry_count`   INT NOT NULL DEFAULT 0,
  `published_at`  DATETIME NULL,
  `last_error`    VARCHAR(500) NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_event_request` (`request_id`),       -- 幂等：同 request 不重复
  UNIQUE KEY `uk_event_id` (`event_id`),
  KEY `idx_status_retry` (`status`,`retry_count`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='领域事件出口/幂等';
```

### 2.2 `t_order_segment`（多式联运段）

```sql
CREATE TABLE t_order_segment (
  `id` BIGINT UNSIGNED AUTO_INCREMENT,
  `order_id` BIGINT UNSIGNED NOT NULL,
  `segment_no` INT NOT NULL DEFAULT 1,
  `segment_mode` VARCHAR(16) NOT NULL COMMENT 'SEA/AIR/RAIL/LAND/TRUCK/WMS',
  `origin_id` BIGINT UNSIGNED NULL,
  `dest_id` BIGINT UNSIGNED NULL,
  `carrier_id` BIGINT UNSIGNED NULL,
  `etd DATE NULL, ` `eta DATE NULL,`
  `segment_status` VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  `handover_event_id` BIGINT UNSIGNED NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_seg` (`order_id`,`segment_no`),
  KEY `idx_carrier` (`carrier_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='委托单段（多式联运）';
```

### 2.3 `t_milestone_instance`

```sql
CREATE TABLE t_milestone_instance (
  `id` BIGINT UNSIGNED AUTO_INCREMENT,
  `order_id` BIGINT UNSIGNED NOT NULL,
  `mile_code` VARCHAR(16) NOT NULL COMMENT 'M-01..M-12',
  `reached_at` DATETIME NULL,
  `source_event_id` BIGINT UNSIGNED NULL,
  `sla_planned_at` DATETIME NULL,
  `status` VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_mile` (`order_id`,`mile_code`),
  KEY `idx_status_sla` (`order_status`,`sla_planned_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='里程碑实例';
```
（`idx_status_sla` 中 `order_status` 笔误，实际为 `status`，落库以 `status` 为准。）

### 2.4 `t_eta`

```sql
CREATE TABLE t_eta (
  `id` BIGINT UNSIGNED AUTO_INCREMENT,
  `related_biz_type` VARCHAR(16) NOT NULL,
  `related_biz_id` BIGINT UNSIGNED NOT NULL,
  `target_event` VARCHAR(16) NOT NULL,
  `predicted_at` DATETIME NOT NULL,
  `confidence` DECIMAL(5,4) NULL,
  `status` VARCHAR(16) NOT NULL DEFAULT 'GENERATED',
  `source_model` VARCHAR(64) NULL,
  `confirmed_at` DATETIME NULL,
  PRIMARY KEY (`id`),
  KEY `idx_biz_target` (`related_biz_type`,`related_biz_id`,`target_event`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

### 2.5 WMS 三表

```sql
CREATE TABLE t_wms_transfer (
  `id` BIGINT UNSIGNED AUTO_INCREMENT,
  `transfer_no` VARCHAR(32) NOT NULL,
  `from_warehouse_id` BIGINT UNSIGNED NOT NULL,
  `to_warehouse_id` BIGINT UNSIGNED NOT NULL,
  `owner_id` BIGINT UNSIGNED NULL,
  `transfer_status` VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
  `qty` DECIMAL(18,3) NOT NULL DEFAULT 0,
  `created_by` BIGINT, `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`), UNIQUE KEY (`transfer_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE t_wms_cycle_count (
  `id` BIGINT UNSIGNED AUTO_INCREMENT,
  `count_no` VARCHAR(32) NOT NULL,
  `warehouse_id` BIGINT UNSIGNED NOT NULL,
  `scope` VARCHAR(255) NULL,
  `count_status` VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
  `diff_count` INT NOT NULL DEFAULT 0,
  `diff_handled` TINYINT NOT NULL DEFAULT 0,
  `counted_by` BIGINT NULL,
  PRIMARY KEY (`id`), UNIQUE KEY (`count_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE t_wms_vas_task (
  `id` BIGINT UNSIGNED AUTO_INCREMENT,
  `task_no` VARCHAR(32) NOT NULL,
  `task_type` VARCHAR(16) NOT NULL COMMENT 'PALLET/SL-ON/REPACK/SORT/REINFO',
  `order_id` BIGINT UNSIGNED NULL,
  `wave_no` VARCHAR(32) NULL,
  `task_status` VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  `qty` DECIMAL(18,3) DEFAULT 0,
  `photo_ids` VARCHAR(255) NULL,
  PRIMARY KEY (`id`), UNIQUE KEY (`task_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

### 2.6 财务 / 满意度

```sql
CREATE TABLE t_order_profit_snapshot (
  `id` BIGINT UNSIGNED AUTO_INCREMENT,
  `order_id` BIGINT UNSIGNED NOT NULL,
  `snapshot_version` INT NOT NULL DEFAULT 1,
  `revenue_amount` DECIMAL(18,2) NOT NULL DEFAULT 0,
  `cost_amount` DECIMAL(18,2) NOT NULL DEFAULT 0,
  `gross_profit` DECIMAL(18,2) NOT NULL DEFAULT 0,
  `fx_rate` DECIMAL(12,6) NULL,
  `snapshot_at` DATETIME NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_ver` (`order_id`,`snapshot_version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE t_satisfaction (
  `id` BIGINT UNSIGNED AUTO_INCREMENT,
  `ticket_id` BIGINT UNSIGNED NULL,
  `order_id` BIGINT UNSIGNED NULL,
  `customer_id` BIGINT UNSIGNED NOT NULL,
  `score` TINYINT NOT NULL CHECK (`score` BETWEEN 1 AND 5),
  `comment` VARCHAR(500) NULL,
  `channel` VARCHAR(32) NULL,
  `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

### 2.7 规则 / 审批 / 配置

```sql
CREATE TABLE t_rule (
  `id` BIGINT UNSIGNED AUTO_INCREMENT,
  `rule_code` VARCHAR(64) NOT NULL,
  `rule_name` VARCHAR(128) NOT NULL,
  `scope_type` VARCHAR(32) NULL,
  `condition` JSON NULL,
  `action` JSON NULL,
  `priority` INT NOT NULL DEFAULT 100,
  `version` INT NOT NULL DEFAULT 1,
  `enable_flag` TINYINT NOT NULL DEFAULT 1,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_rule` (`rule_code`,`version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE t_sla_rule (
  `id` BIGINT UNSIGNED AUTO_INCREMENT,
  `sla_type` VARCHAR(32) NOT NULL,
  `priority` VARCHAR(8) NOT NULL,
  `first_due` VARCHAR(16) NULL,
  `resolve_due` VARCHAR(16) NULL,
  `escalate_ladder` VARCHAR(255) NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_sla` (`sla_type`,`priority`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE t_approval_record (
  `id` BIGINT UNSIGNED AUTO_INCREMENT,
  `biz_type` VARCHAR(32) NOT NULL,
  `biz_id` BIGINT UNSIGNED NOT NULL,
  `node` VARCHAR(64) NOT NULL,
  `approver_id` BIGINT UNSIGNED NULL,
  `action` VARCHAR(16) NOT NULL,
  `comment` VARCHAR(500) NULL,
  `decision_at` DATETIME NULL,
  PRIMARY KEY (`id`),
  KEY `idx_biz` (`biz_type`,`biz_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE t_md_change_log (
  `id` BIGINT UNSIGNED AUTO_INCREMENT,
  `md_type` VARCHAR(32) NOT NULL,
  `md_id` BIGINT UNSIGNED NOT NULL,
  `version` INT NOT NULL DEFAULT 1,
  `change_payload` JSON NULL,
  `approver_id` BIGINT UNSIGNED NULL,
  `status` VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  `effect_at` DATETIME NULL,
  PRIMARY KEY (`id`),
  KEY `idx_md` (`md_type`,`md_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE t_extension_field (
  `id` BIGINT UNSIGNED AUTO_INCREMENT,
  `biz_type` VARCHAR(32) NOT NULL,
  `biz_id` BIGINT UNSIGNED NOT NULL,
  `field_code` VARCHAR(64) NOT NULL,
  `field_value` VARCHAR(512) NULL,
  PRIMARY KEY (`id`),
  KEY `idx_ext` (`biz_type`,`biz_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

---

## 三、业务单号唯一索引（全部现有业务表）

> 统一：`UNIQUE KEY uk_<表>_<no> (<no>)`；重点列示如下（其余同理追加）。

| 表 | 业务号列 | 约束 |
| --- | --- | --- |
| t_inquiry | inquiry_no | UNIQUE ON inquiry_no |
| t_quotation | quotation_no | UNIQUE |
| t_order | order_no | UNIQUE |
| t_booking | booking_no | UNIQUE |
| t_bill_of_lading | bl_no | UNIQUE |
| t_shipping_instruction | si_no | UNIQUE |
| t_container | container_no | UNIQUE |
| t_customs_declaration | declaration_no / customs_no | UNIQUE ×2 |
| t_wms_inbound / outbound | inbound_no / outbound_no | UNIQUE |
| t_ar_invoice / ap_invoice | ar_no / ap_no | UNIQUE |
| t_payment_received / paid | payment_no | UNIQUE |
| t_statement | statement_no | UNIQUE |
| t_customer / t_partner / t_port / t_hs_code | *_code / *_no | UNIQUE |
| t_opportunity / t_ticket | opp_no / ticket_no | UNIQUE |
| t_code_rule | rule_code | UNIQUE |

---

## 四、关键索引 / 外键建议

| 用途 | 索引 |
| --- | --- |
| 查询加速 | `t_order(org_id, order_status, created_at)` |
| 委托单→订舱 | `t_booking(order_id)` |
| 订舱→提单/补料 | `t_bill_of_lading(booking_id)`、`t_shipping_instruction(booking_id)` |
| 跟踪查询 | `t_tracking_event(related_biz_type, related_biz_id, event_time)` |
| 财务对账 | `t_ar_invoice(customer_id, bill_period)`、`t_ap_invoice(partner_id, bill_period)` |
| 收款核销 | `t_payment_received_alloc(ar_invoice_id)` |
| 幂等写 | 所有发送/入站事件用 `request_id` UNIQUE |
| 库存可用 | `t_inventory(warehouse_id, sku, location_code)` |

> **外键策略**：生产建议**逻辑外键（仅索引，不建物理 FK）**，便于 SaaS 分库与大流量；关键父子关系（如 `t_booking.order_id→t_order.id`）如需强约束可后续开启。本版统一先落索引。

---

## 五、租户与扩展落位清单

- 本版所有业务表 DDL 未逐表补充 `org_id`；**落库前统一批量补 `org_id` 列 + `idx_org_created`**（见第一章通用尾列）。
- 个性化字段走 `t_extension_field`（EAV），不改主表结构，支撑直客/同行/电商/项目差异。
- 主数据与规则用 `t_md_change_log` / `t_rule.version` 做版本化。
- 事件统一走 `t_event_outbox`，`request_id` 唯一索引 + `event_id` 唯一索引保证 exactly-once。

---

## 六、待评审确认项

- [ ] 是否启用物理外键（默认否）。
- [ ] `org_id` 是否本期即落地（默认本期批量补列）。
- [ ] `t_order_segment` 本期落位 or 仅字段占位（默认落位）。
- [ ] 满意度独立表 vs 并入工单（默认独立表，见 2.6）。
- [ ] 规则/SLA 先落地为配置表 or 常量（默认落地为表）。


---

## 七、扩展模块核心 DDL（模块 20–24）

> 依据 `02 v2.1` 十七~二十一层；只列代表性表（业务单号唯一索引 + 关键状态 + 占用/占用外键逻辑化），其余表同理套用全局约定。

### 7.1 配送调度（20）

```sql
CREATE TABLE t_delivery_order (
  `id` BIGINT UNSIGNED AUTO_INCREMENT,
  `delivery_no` VARCHAR(32) NOT NULL,
  `source_type` VARCHAR(16) NOT NULL,
  `order_id` BIGINT UNSIGNED NULL,
  `outbound_id` BIGINT UNSIGNED NULL,
  `warehouse_id` BIGINT UNSIGNED NULL,
  `customer_id` BIGINT UNSIGNED NOT NULL,
  `vehicle_id` BIGINT UNSIGNED NULL,
  `driver_id` BIGINT UNSIGNED NULL,
  `delivery_status` VARCHAR(16) NOT NULL DEFAULT 'PENDING_DISPATCH',
  `sign_type` VARCHAR(8) NULL,
  `handler_id` BIGINT UNSIGNED NULL,
  `planned_at` DATETIME NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_delivery_no` (`delivery_no`),
  KEY `idx_origin` (`warehouse_id`,`delivery_status`),
  KEY `idx_vehicle` (`vehicle_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='配送订单';
```

### 7.2 逆向物流（21）

```sql
CREATE TABLE t_return_request (
  `id` BIGINT UNSIGNED AUTO_INCREMENT,
  `return_no` VARCHAR(32) NOT NULL,
  `customer_id` BIGINT UNSIGNED NOT NULL,
  `order_id` BIGINT UNSIGNED NULL,
  `return_type` VARCHAR(16) NOT NULL,
  `request_status` VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
  `approver_id` BIGINT UNSIGNED NULL,
  `decision_at` DATETIME NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_return_no` (`return_no`),
  KEY `idx_customer` (`customer_id`,`request_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='退货申请';
```

### 7.3 需求预测（22）

```sql
CREATE TABLE t_forecast_run (
  `id` BIGINT UNSIGNED AUTO_INCREMENT,
  `run_no` VARCHAR(32) NOT NULL,
  `dimension` VARCHAR(16) NOT NULL,
  `time_granularity` VARCHAR(8) NOT NULL,
  `horizon_days` INT NOT NULL DEFAULT 30,
  `model_code` VARCHAR(64) NULL,
  `run_status` VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  `started_at` DATETIME NULL,
  `completed_at` DATETIME NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_forecast_run` (`run_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='预测批次';
```

### 7.4 采购供应（23）

```sql
CREATE TABLE t_purchase_order (
  `id` BIGINT UNSIGNED AUTO_INCREMENT,
  `po_no` VARCHAR(32) NOT NULL,
  `pr_id` BIGINT UNSIGNED NULL,
  `supplier_id` BIGINT UNSIGNED NOT NULL,
  `contract_id` BIGINT UNSIGNED NULL,
  `po_status` VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
  `order_amount` DECIMAL(18,2) NOT NULL DEFAULT 0,
  `eta` DATE NULL,
  `received_at` DATETIME NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_po_no` (`po_no`),
  KEY `idx_supplier` (`supplier_id`,`po_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='采购订单';
```

### 7.5 装卸 / 换装转运（24）

```sql
CREATE TABLE t_transshipment_task (
  `id` BIGINT UNSIGNED AUTO_INCREMENT,
  `task_no` VARCHAR(32) NOT NULL,
  `order_id` BIGINT UNSIGNED NOT NULL,
  `from_segment_id` BIGINT UNSIGNED NULL,
  `to_segment_id` BIGINT UNSIGNED NULL,
  `transit_hub_id` BIGINT UNSIGNED NULL,
  `ts_mode` VARCHAR(16) NOT NULL,
  `ts_status` VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  `resource_id` BIGINT UNSIGNED NULL,
  `planned_at` DATETIME NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tsk_no` (`task_no`),
  KEY `idx_order` (`order_id`,`ts_status`),
  KEY `idx_hub` (`transit_hub_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='转运任务';
```

> 其余扩展表（配送明细/司机/排线、逆向单/质检、预测明细/模型、采购申请/明细/合同、装卸作业/资源/中转节点）均新建 `UNIQUE KEY uk_<表>_<no>` 并套用全局审计尾列。

## 八、扩展模块承接关系示意

```
22 需求预测 ──建议──> 23 采购供应 ──应付──> 10 财务
06 干线段 <──交接── 24 装卸/换装转运 ──接驳──> 20 配送调度
21 逆向物流 ──入库──> 09 仓储WMS ──出库──> 20 配送调度(末端)
```

