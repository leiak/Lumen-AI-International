-- 与生产 V41 对齐: finance 测试夹具的 t_billing_accrual.rule_id 同样改可空。
--
-- 为什么不直接改 V1__billing_schema.sql: 已应用的迁移不改 (Flyway checksum 纪律)。
-- 夹具必须跟生产 schema 一致 —— 否则将来在这个模块加 IT 会踩同一个 1364 的坑,
-- 而那正是 4.5D-1 修掉的东西。
ALTER TABLE t_billing_accrual MODIFY COLUMN rule_id BIGINT NULL;
