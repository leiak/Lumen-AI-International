-- =============================================================
-- V22: Dispatch demo seed data (车辆 / 司机 / 承运人)
-- Iteration 4.1 PR2
-- =============================================================
-- 目标：为 dev / test 环境预置 3 车型 + 5 司机 + 2 承运人。
--   全部走 tenant_id=1（默认 dev tenant），status 全部 ACTIVE / IDLE，
--   唯一约束只覆盖 (tenant_id, plate_no|phone|code, deleted)。
--   后续 PR (MasterDataIT) 若再加数据，应遵循 WmsTestFixtures 用 nanoTime 后缀
--   避免撞唯一约束 — 本 seed 是 demo 起步数据，不是测试隔离数据。
--
-- 表列约束（参 V19__dispatch_schema.sql）：
--   wms_vehicle.chk_wms_vehicle_load   : max_load_kg > 0 AND max_volume_m3 > 0
--   wms_driver.chk_wms_driver_status   : status IN ('ACTIVE','SUSPENDED')
--   wms_carrier 无 CHECK
-- =============================================================

-- ----------------------------------------------------------------
-- 车辆：3 车型 × 1 = 3 行
--   京A00001  SMALL  500kg  /   5m3
--   京A00002  MEDIUM 2000kg /  20m3
--   京A00003  LARGE  5000kg /  50m3
-- 司机绑定：张师傅/李师傅/王师傅；承运人：顺丰(1)/顺丰(1)/圆通(2)
-- ----------------------------------------------------------------
INSERT INTO wms_vehicle
    (tenant_id, plate_no, type, max_load_kg, max_volume_m3, status, driver_id, carrier_id, remark, created_at, updated_at, deleted, version)
VALUES
(1, '京A00001', 'SMALL',  500.000,    5.000, 'IDLE',  1, 1, 'demo 起步车辆 - 顺丰 SMALL',      NOW(), NOW(), 0, 1),
(1, '京A00002', 'MEDIUM', 2000.000,  20.000, 'IDLE',  2, 1, 'demo 起步车辆 - 顺丰 MEDIUM',     NOW(), NOW(), 0, 1),
(1, '京A00003', 'LARGE',  5000.000,  50.000, 'IDLE',  3, 2, 'demo 起步车辆 - 圆通 LARGE',      NOW(), NOW(), 0, 1)
;

-- ----------------------------------------------------------------
-- 司机：5 行
--   张/李/王师傅：分别绑给三辆车（见上 wms_vehicle 引用）
--   赵/钱师傅：未绑车（演示 FREE 池）
-- ----------------------------------------------------------------
INSERT INTO wms_driver
    (tenant_id, name, phone, license_no, status, remark, created_at, updated_at, deleted, version)
VALUES
(1, '张师傅', '13800000001', 'BJ-DRV-001', 'ACTIVE',    'demo 起步司机', NOW(), NOW(), 0, 1),
(1, '李师傅', '13800000002', 'BJ-DRV-002', 'ACTIVE',    'demo 起步司机', NOW(), NOW(), 0, 1),
(1, '王师傅', '13800000003', 'BJ-DRV-003', 'ACTIVE',    'demo 起步司机', NOW(), NOW(), 0, 1),
(1, '赵师傅', '13800000004', 'BJ-DRV-004', 'ACTIVE',    'demo 备车司机', NOW(), NOW(), 0, 1),
(1, '钱师傅', '13800000005', 'BJ-DRV-005', 'ACTIVE',    'demo 备车司机', NOW(), NOW(), 0, 1)
;

-- ----------------------------------------------------------------
-- 承运人：2 行（自有 vs 外协 演示）
--   SF : 顺丰 (主合作)
--   YT : 圆通 (备用 / 多承运人占位)
-- ----------------------------------------------------------------
INSERT INTO wms_carrier
    (tenant_id, code, name, contact, status, created_at, updated_at, deleted, version)
VALUES
(1, 'SF', '顺丰物流', '4008-111-111', 'ACTIVE', NOW(), NOW(), 0, 1),
(1, 'YT', '圆通物流', '4008-222-222', 'ACTIVE', NOW(), NOW(), 0, 1)
;

-- =============================================================
-- 备注：
--   V20 中 vehicle/dispatcher 状态机行已 seed；
--   本迁移 demo 数据的 vehicle.status='IDLE' / driver.status='ACTIVE'
--   / carrier.status='ACTIVE' 与状态机 INITIAL 状态完全对齐，
--   这样 PR3 MasterDataIT 可以直接拿 ID=1..3 的车辆 / 1..2 的承运人做
--   'assign' / 'dispatch' 转换的端到端集成测试，不必自己造数据。
-- =============================================================
