-- =============================================================
-- V39: t_reconciliation 加商家异议三列 (4.5C-2)
--
-- 背景:
--   ReconciliationServiceImpl.runReconciliation 对每一笔非零差异已经自动建好
--   一张 DRAFT 调账单 (只 create, 从不 submit), 并把 recon 置为 ADJUSTMENT_CREATED。
--   商家看到差异时调账单已经存在 —— 他要表达的是「我不认可这个差额」, 而不是
--   「再建一张调账单」(那是对同一笔差异的双重调账)。
--
--   本迁移给商家一个留下主张的位置: 声明 + 理由 + 时间。非空 dispute_at 即代表
--   该期次已被异议过 (同一期只允许一次), 兼作幂等标记。
--
--   落在 recon 行而非 adjustment 行: 商家异议的自然粒度是
--   (period, direction, counterparty, currency), 正是 recon 的粒度
--   (uk_recon_tenant_period_cpty); 而 t_billing_adjustment 没有 counterparty_id
--   也没有 period, 放那儿得新建关联表。
--
--   列约定:
--   DATETIME(3) 与该表既有 created_at / updated_at 同精度 (V31)。
--   无 DB 侧 DEFAULT —— 异议时间是事件时间不是行创建时间, 由应用写入。
--   dispute_at 落在 recon 行上, 所以"该期已异议"实际是按 recon 行判定的, 即
--   (period, direction, counterparty, currency) 这个粒度 —— 同一对手方开了
--   CNY 和 USD 两行时两行各自可独立异议, 这是符合预期的。
--   与 V30 的多列批量 ADD COLUMN ... AFTER ... 写法一致。
--
--   不动 V31 本身 —— Flyway 已记录其 checksum, 改动会导致校验失败。
-- =============================================================

ALTER TABLE t_reconciliation
  ADD COLUMN dispute_amount DECIMAL(18,4) NULL COMMENT '商家主张金额, NULL = 仅质疑不主张' AFTER remark,
  ADD COLUMN dispute_reason VARCHAR(500) NULL COMMENT '商家异议理由' AFTER dispute_amount,
  ADD COLUMN dispute_at     DATETIME(3)   NULL COMMENT '异议时间, 非空即该期已被异议' AFTER dispute_reason
;
