package com.lumen.finance;

/**
 * lumen-finance 模块常量 (Iteration 4.3.1).
 *
 * <p>业务类型 / direction / counterparty_type / 状态字符串等全局常量.
 * 与 lumen-cs 的 CsConstants 风格一致 (spec §3, §5).
 */
public final class FinanceConstants {

    private FinanceConstants() {}

    // 业务类型
    public static final String BIZ_LAST_MILE      = "LAST_MILE";
    public static final String BIZ_WAREHOUSE_IN   = "WAREHOUSE_IN";
    public static final String BIZ_WAREHOUSE_OUT  = "WAREHOUSE_OUT";

    // 触发计费的订单终态 (lumen-dispatch DispatchConstants.DO_SIGNED 字符串)
    // 独立存放: lumen-finance 不依赖 lumen-dispatch — 仅作为比对值.
    public static final String FIN_TRIGGER_STATE_LAST_MILE = "SIGNED";

    // direction
    public static final String DIR_RECEIVABLE = "RECEIVABLE";
    public static final String DIR_PAYABLE    = "PAYABLE";

    // counterparty_type
    public static final String CP_CUSTOMER  = "CUSTOMER";
    public static final String CP_CARRIER   = "CARRIER";
    public static final String CP_WAREHOUSE = "WAREHOUSE";

    // currency (4.3.1 only CNY; 4.3.2 adds USD)
    public static final String CURRENCY_CNY = "CNY";

    // 规则 status
    public static final String RULE_STATUS_DRAFT    = "DRAFT";
    public static final String RULE_STATUS_ACTIVE   = "ACTIVE";
    public static final String RULE_STATUS_DISABLED = "DISABLED";
    public static final String RULE_STATUS_INVALID  = "INVALID";

    // accrual status
    public static final String ACCRUAL_STATUS_OPEN       = "OPEN";
    public static final String ACCRUAL_STATUS_LOCKED     = "LOCKED";
    public static final String ACCRUAL_STATUS_CONVERTED  = "CONVERTED";
    public static final String ACCRUAL_STATUS_RELEASED   = "RELEASED";
    public static final String ACCRUAL_STATUS_OPEN_FAILED = "OPEN_FAILED";

    // invoice status
    public static final String INV_STATUS_DRAFT       = "DRAFT";
    public static final String INV_STATUS_PENDING_L1  = "PENDING_L1";
    public static final String INV_STATUS_PENDING_L2  = "PENDING_L2";   // 4.3.2 — L1 走完, 等 L2 终审
    public static final String INV_STATUS_L1_APPROVED = "L1_APPROVED";  // 4.3.1 保留 (兼容回退场景)
    public static final String INV_STATUS_L2_APPROVED = "L2_APPROVED";  // 4.3.2 — L2 终审通过
    public static final String INV_STATUS_PUSHED      = "PUSHED";
    public static final String INV_STATUS_REJECTED    = "REJECTED";

    // event_log process_status
    public static final String EVT_STATUS_RECEIVED = "RECEIVED";
    public static final String EVT_STATUS_SUCCESS  = "SUCCESS";
    public static final String EVT_STATUS_FAILED   = "FAILED";
    public static final String EVT_STATUS_NO_RULE  = "NO_RULE";

    // rule_item item_type
    public static final String ITEM_TYPE_BASE     = "BASE";
    public static final String ITEM_TYPE_DISTANCE = "DISTANCE";
    public static final String ITEM_TYPE_WEIGHT   = "WEIGHT";
    public static final String ITEM_TYPE_EXTRA    = "EXTRA";
    public static final String ITEM_TYPE_TAX      = "TAX";

    // rule audit change_type
    public static final String AUDIT_CHANGE_CREATE  = "CREATE";
    public static final String AUDIT_CHANGE_UPDATE  = "UPDATE";
    public static final String AUDIT_CHANGE_DISABLE = "DISABLE";
    public static final String AUDIT_CHANGE_DELETE  = "DELETE";

    // state machine names
    public static final String SM_BILLING_INVOICE = "billing_invoice";
    public static final String SM_BILLING_ACCRUAL = "billing_accrual";
    public static final String SM_BILLING_ADJUSTMENT = "billing_adjustment";

    // 表达式求值超时 (ms)
    public static final long EXPRESSION_TIMEOUT_MS = 100L;
    // 表达式缓存上限
    public static final int EXPRESSION_CACHE_MAX_SIZE = 1000;
    // 单据 l1/l2 comment 最大长度 (匹配 V26 l1_comment/l2_comment VARCHAR(500))
    public static final int INVOICE_L1_COMMENT_MAX_LEN = 500;
    // 4.3.2 — L2 comment 单独限长 (与 L1 对齐, 共享 VARCHAR(500))
    public static final int INVOICE_L2_COMMENT_MAX_LEN = 500;

    // ====== 4.3.2 PR4 — AdjustmentService ======

    // adjustment status (V30 seed, 与 billing_invoice 对齐, 仅加 POSTED 终态)
    public static final String ADJ_STATUS_DRAFT       = "DRAFT";
    public static final String ADJ_STATUS_PENDING_L1  = "PENDING_L1";
    public static final String ADJ_STATUS_PENDING_L2  = "PENDING_L2";
    public static final String ADJ_STATUS_L2_APPROVED = "L2_APPROVED";
    public static final String ADJ_STATUS_POSTED      = "POSTED";
    public static final String ADJ_STATUS_REJECTED    = "REJECTED";

    // adjustment type
    public static final String ADJ_TYPE_CREDIT = "CREDIT";
    public static final String ADJ_TYPE_DEBIT  = "DEBIT";

    // adjustment reason 最小长度 (V26 reason VARCHAR(500) NOT NULL — 4.3.2 起至少 10 字符)
    public static final int ADJUSTMENT_REASON_MIN_LEN = 10;
    // L1 / L2 comment 截断上限 (与 invoice 对齐, 共享 VARCHAR(500))
    public static final int ADJUSTMENT_L1_COMMENT_MAX_LEN = 500;
    public static final int ADJUSTMENT_L2_COMMENT_MAX_LEN = 500;

    // ====== 4.4 PR1 — PaymentRecord + MonthlyClosing ======

    // payment_record source
    public static final String PAY_SOURCE_MANUAL = "MANUAL";
    public static final String PAY_SOURCE_CSV    = "CSV";

    // monthly_closing status
    public static final String CLOSING_STATUS_OPEN        = "OPEN";
    public static final String CLOSING_STATUS_IN_PROGRESS = "IN_PROGRESS";
    public static final String CLOSING_STATUS_CLOSED      = "CLOSED";

    // reconciliation status
    public static final String RECON_STATUS_PENDING             = "PENDING";
    public static final String RECON_STATUS_ADJUSTMENT_CREATED  = "ADJUSTMENT_CREATED";
    public static final String RECON_STATUS_RESOLVED            = "RESOLVED";
    public static final String RECON_STATUS_WAIVED              = "WAIVED";

    // state machine name
    public static final String SM_MONTHLY_CLOSING = "monthly_closing";

    // period 格式 YYYY-MM
    public static final String PERIOD_PATTERN = "^\\d{4}-(0[1-9]|1[0-2])$";

    // reopen reason 最小长度
    public static final int CLOSING_REOPEN_REASON_MIN_LEN = 10;
}