package com.lumen.finance.exception;

import com.lumen.common.error.ErrorCode;
import lombok.Getter;

/**
 * Finance 模块业务错误码 (Iteration 4.3.1).
 *
 * <p>错误码区间: 404_001..404_004 (404) / 400_001..400_005 (400) / 409_001..409_003 (409),
 * 6 位 HTTP-status-prefixed 编码, 与 lumen-cs CsErrorCode 风格一致.
 * 4.3.1 范围 12 个 code, 4.3.2/4.3.3 续号.
 *
 * <p>HTTP 状态码由 {@link #httpStatus} 字段决定 (lumen-common GlobalExceptionHandler 自动映射).
 */
@Getter
public enum FinanceErrorCode implements ErrorCode {
    RULE_NOT_FOUND                    (404_001, "Rule not found",                           404),
    RULE_ITEM_NOT_FOUND               (404_002, "Rule item not found",                      404),
    ACCRUAL_NOT_FOUND                 (404_003, "Accrual not found",                        404),
    INVOICE_NOT_FOUND                 (404_004, "Invoice not found",                        404),
    EXPRESSION_INVALID                (400_001, "Expression invalid",                       400),
    EXPRESSION_TIMEOUT                (400_002, "Expression evaluation timeout",            400),
    EXPRESSION_UNKNOWN_VARIABLE       (400_003, "Expression references unknown variable",   400),
    DUPLICATE_RULE                    (409_001, "Rule for (tenant, business_type, direction, counterparty_type, effective_from) already exists", 409),
    INVOICE_NO_CONFLICT               (409_002, "Invoice number generation conflict after 3 retries", 409),
    INVALID_STATE_TRANSITION          (409_003, "Invalid state transition for billing invoice", 409),
    RULE_NOT_ACTIVE                   (400_004, "Rule is not in ACTIVE status",             400),
    EXPRESSION_SANDBOX_VIOLATION      (400_005, "Expression violates sandbox policy",       400),
    INVOICE_REJECT_REASON_REQUIRED    (400_006, "Invoice reject reason is required",       400),
    RATE_UNAVAILABLE                  (400_007, "汇率数据不可用 (Rate unavailable)",          400),
    RATE_SYNC_FAILED                  (400_008, "汇率同步失败 (Rate sync failed)",            400),
    L1_L2_SAME_OPERATOR               (400_009, "L1 与 L2 不能同一操作员 (L1/L2 same operator)", 400),
    TAX_RATE_NOT_FOUND                (400_010, "税率未配置 (Tax rate not found)",            400),
    // ====== 4.3.2 PR4 — AdjustmentService 新增 ======
    ADJUSTMENT_AMOUNT_INVALID         (400_011, "调账单金额必须为正 (Adjustment amount must be positive)", 400),
    ADJUSTMENT_REASON_REQUIRED        (400_012, "调账单原因至少 10 字符 (Adjustment reason required, min 10 chars)", 400),
    ADJUSTMENT_CURRENCY_MISMATCH      (400_013, "调账单币种必须与源单据一致 (Adjustment currency must match source invoice)", 400),
    ADJUSTMENT_SOURCE_NOT_APPROVED    (400_014, "源单据未达 L2_APPROVED 不可调账 (Source invoice must be L2_APPROVED)", 400),
    ADJUSTMENT_NOT_FOUND              (400_015, "调账单未找到 (Adjustment not found)",            400);

    private final int code;
    private final String message;
    private final int httpStatus;

    FinanceErrorCode(int code, String message, int httpStatus) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
    }
}
