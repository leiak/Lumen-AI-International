-- t_billing_accrual.rule_id 由 NOT NULL 改为可空。
--
-- 理由 (Iteration 4.5D-1): estimate 阶段失败时, 计费规则归属本就未知 ——
-- BillingEngineServiceImpl.writeEstimateAndAccrueFailureAudit 写的 OPEN_FAILED 取证行
-- 没有 rule id 可填; 而 override 命中时 (EstimateResult.SOURCE_OVERRIDE) 本就没有
-- rule 归属。强制 NOT NULL 会让这行取证直接写不进去 (MySQL strict mode 报 1364),
-- 结果是「订单提交成功、账上零行痕迹」。
--
-- 该列无外键约束 (t_billing_accrual 只有 uk_accrual 与 idx_status), 改列无牵连。
--
-- MODIFY 会丢掉未在语句里重述的列属性 —— 本次不丢: 原列 (V26__billing_schema_and_rbac.sql:97)
-- 是裸的 `rule_id BIGINT NOT NULL`, 无 DEFAULT、无列注释, BIGINT 也不带字符集/排序规则属性,
-- 所以这里只需重述类型与可空性。**后人若要「顺手优化」这条迁移 (加 DEFAULT / 加注释 /
-- 改类型), 必须把原属性一并写全**, 否则会静默丢列属性。
--
-- 显式声明 ALGORITHM=INPLACE, LOCK=NONE: MySQL 8.0 对「NOT NULL -> NULL」支持 INPLACE 且
-- 不重建表(反向的 NULL -> NOT NULL 才需要 COPY), 但**不写**这两个子句时由服务端自选算法 ——
-- 一旦目标环境不支持, 会**静默退化成 ALGORITHM=COPY(整表重建 + 写阻塞)**, 而不是干脆报错。
-- 写死之后, 不支持就直接报错 —— 这正是想要的。本仓所有环境都是 mysql:8.0
-- (docker-compose.dev.yml / docker-compose.test.yml / IntegrationBase 的 testcontainers),
-- MySQL 8.0 对该变更支持 INPLACE + LOCK=NONE + 不重建表。
--
-- **回滚路径**: 反向的 `MODIFY COLUMN rule_id BIGINT NOT NULL` 在表里存在 NULL 行时**会失败**
-- (MySQL strict mode 报 1364) —— 而 4.5D-1 之后必然产生 `rule_id IS NULL` 的 OPEN_FAILED 行
-- → **回滚前必须先清理(或补齐)这些行**, 否则那条 ALTER 跑不通。
--
-- V1..V40 一律不动 (Flyway checksum)。**本文件从未被任何环境应用过** —— dev 库的
-- flyway_schema_history 最新只到 V33(已实测), V34..V41 从未落地; bootstrap IT 用
-- testcontainers 每次全新建库 → 校验和每次重算。所以可以直接改本文件, 不需要新开 V42。
ALTER TABLE t_billing_accrual
    MODIFY COLUMN rule_id BIGINT NULL,
    ALGORITHM=INPLACE,
    LOCK=NONE;
