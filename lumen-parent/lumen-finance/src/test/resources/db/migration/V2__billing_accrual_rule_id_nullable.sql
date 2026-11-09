-- 与生产 V41 对齐: finance 测试夹具的 t_billing_accrual.rule_id 同样改可空。
--
-- 为什么不直接改 V1__billing_schema.sql: 已应用的迁移不改 (Flyway checksum 纪律)。
-- 夹具必须跟生产 schema 一致 —— 否则将来在这个模块加 IT 会踩同一个 1364 的坑,
-- 而那正是 4.5D-1 修掉的东西。
--
-- ALGORITHM=INPLACE, LOCK=NONE 与生产 V41 保持一致 —— MySQL 8.0 对「NOT NULL -> NULL」支持
-- INPLACE 且不重建表; 写死这两个子句, 目标环境不支持时直接报错, 而不是静默退化成 COPY
-- (整表重建 + 写阻塞)。本模块的测试库也是 mysql:8.0。
--
-- **回滚路径**同 V41: 表里存在 `rule_id IS NULL` 的行时, 改回 NOT NULL 会报 1364
-- → 回滚前必须先清理(或补齐)这些行。
--
-- 本文件同样从未被任何环境应用过 (夹具库每次重建) → 可以直接改, 不需要新开 V3。
ALTER TABLE t_billing_accrual
    MODIFY COLUMN rule_id BIGINT NULL,
    ALGORITHM=INPLACE,
    LOCK=NONE;
