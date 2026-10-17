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
    public static final String INV_STATUS_L1_APPROVED = "L1_APPROVED";
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

    // 表达式求值超时 (ms)
    public static final long EXPRESSION_TIMEOUT_MS = 100L;
    // 表达式缓存上限
    public static final int EXPRESSION_CACHE_MAX_SIZE = 1000;
}