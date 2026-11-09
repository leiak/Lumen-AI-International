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
-- **回滚路径**(与生产 V41 同款, 展开写 —— 夹具文件是独立读的, 将来单独看它的人也要看得到):
-- 反向的 `MODIFY COLUMN rule_id BIGINT NOT NULL` 在表里存在 NULL 行时**会失败**:
--   - strict 模式(默认 sql_mode = STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION)
--     → ERROR **1138** "Data truncation: Invalid use of NULL value";
--   - 非 strict + 带 ALGORITHM=INPLACE → ERROR **1846**
--     "ALGORITHM=INPLACE is not supported. Reason: cannot silently convert NULL values, ...";
--   - 非 strict + **不带** ALGORITHM → **静默把 NULL 转成 0**(最危险: 「归属未知」被悄悄改成
--     「规则 0」, 归因口径失真且**没有任何一行日志**)。回滚时务必**带上 ALGORITHM=INPLACE**,
--     或先清理/补齐这些 `rule_id IS NULL` 的 OPEN_FAILED 行。
-- (以上三种行为均在 MySQL 8.0.21 上用探针表实测过: 1138 / 1846 / 静默改成 0 三种都复现了。)
-- 注: 正反两个方向**都在 INPLACE 能力内**, 都不需要 ALGORITHM=COPY —— 反向失败是**数据**不允许。
-- (另注: 1364 "Field 'rule_id' doesn't have a default value" 是 **INSERT 省略该列**时的报错,
--  正是 4.5D-1 要修的原始 bug; 反向 ALTER 报的是 **1138**, 两者别混。)
--
-- 本文件同样从未被任何环境应用过 (夹具库每次重建) → 可以直接改, 不需要新开 V3。
ALTER TABLE t_billing_accrual
    MODIFY COLUMN rule_id BIGINT NULL,
    ALGORITHM=INPLACE,
    LOCK=NONE;
