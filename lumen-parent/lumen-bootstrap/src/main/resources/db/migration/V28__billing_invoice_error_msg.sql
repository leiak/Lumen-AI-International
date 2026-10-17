-- =============================================================
-- V28 — Iteration 4.3.1 PR3 代码质量修复 (T3.5): t_billing_invoice 加 error_msg 列
-- =============================================================
-- 背景: 4.3.1 PR3 T3.5 的 BillingInvoiceController.toView 一直返回 errorMsg=null,
--       因为 BillingInvoice entity 没有 errorMsg 字段, 而 t_billing_invoice 表也没有该列.
--       一旦 PUSHED 单据携带错误 (下游推送失败) 需要留痕, 字段就漏掉了.
--       这里加一列 VARCHAR(1000), NULL 默认, 不破坏现有数据.
--
-- 影响: 与 t_billing_accrual.error_msg (V26) 对齐, 同样语义 ("处理失败原因 (若有)").
--       4.3.1 PR3 不写该列 (推 PUSHED 走 4.3.2 推送器), 加列仅为前端正确暴露 + 测试锁定.
--
-- 不回滚: t_billing_invoice 仅加列, 不删除 / 重命名. 唯一约束未动.
-- =============================================================

ALTER TABLE t_billing_invoice
  ADD COLUMN error_msg VARCHAR(1000) DEFAULT NULL COMMENT '处理失败原因 (PUSHED 等状态用; 4.3.1 阶段为空, 4.3.2 推 PUSHED 时回写)' AFTER l1_comment;