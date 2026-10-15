package com.lumen.cs.exception;

import com.lumen.common.error.ErrorCode;
import lombok.Getter;

/**
 * CS 模块业务（customer-service）错误码.
 *
 * <p>遵循 lumen-common 约定（见 {@link ErrorCode}），由 {@code GlobalExceptionHandler}
 * 捕获 {@link com.lumen.cs.exception.CsException} 并映射为对应 HTTP 状态码.
 *
 * <p>错误码区间：404_001..503_409, 400_001..400_003, 409_001..409_002, 503_001, 500_001.
 * 注：本模块错误码采用 6 位编码，与 dispatch / wms 的 5 位编码（60001-60099 等）不同 —
 *      按 spec §3.7 区分 CS 域（4.2 引入）。HTTP 状态码由 {@link #httpStatus} 字段决定。
 */
@Getter
public enum CsErrorCode implements ErrorCode {
    SLA_CONFIG_NOT_FOUND              (404_001, "SLA config not found",                 404),
    BUSINESS_CALENDAR_NOT_FOUND        (404_002, "Business calendar not found",          404),
    HOLIDAY_NOT_FOUND                  (404_003, "Holiday not found",                    404),
    EXCEPTION_NOT_FOUND                (404_004, "Exception not found",                  404),
    SLA_EVENT_NOT_FOUND                (404_005, "SLA event not found",                  404),
    INVALID_RESPONSE_MINUTES           (400_001, "response_minutes must be positive",    400),
    INVALID_RESOLVE_MINUTES            (400_002, "resolve_minutes must be >= response_minutes", 400),
    INVALID_WEEKDAY                    (400_003, "weekday must be 1..7",                 400),
    DUPLICATE_SLA_CONFIG               (409_001, "SLA config for (tenant, severity, level) already exists", 409),
    DUPLICATE_HOLIDAY                  (409_002, "Holiday for (tenant, date) already exists", 409),
    ESCALATION_NO_USER_AVAILABLE       (503_001, "No user available for target escalation level", 503),
    SEVERITY_MAPPING_MISSING           (500_001, "Severity mapping not defined for type: ", 500);

    private final int code;
    private final String message;
    private final int httpStatus;

    CsErrorCode(int code, String message, int httpStatus) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
    }
}