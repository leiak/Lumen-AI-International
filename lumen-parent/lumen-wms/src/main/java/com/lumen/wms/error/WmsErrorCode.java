package com.lumen.wms.error;

import com.lumen.common.error.ErrorCode;
import lombok.Getter;

/**
 * WMS 模块业务错误码.
 *
 * <p>遵循 lumen-common 约定（见 {@link com.lumen.common.error.ErrorCode} 与
 * {@link com.lumen.rbac.error.RbacErrorCode}），由 {@code GlobalExceptionHandler}
 * 捕获 {@link com.lumen.wms.exception.WmsException} 并映射为对应 HTTP 状态码.
 *
 * <p>错误码区间：50001-50099（WMS 业务域）.
 */
@Getter
public enum WmsErrorCode implements ErrorCode {
    WAREHOUSE_NOT_FOUND(50001, "仓库未找到", 404),
    LOCATION_NOT_FOUND (50002, "库位未找到", 404),
    ITEM_NOT_FOUND     (50003, "货品未找到", 404),
    CUSTOMER_NOT_FOUND (50004, "客户未找到", 404),
    DUPLICATE_CODE     (50005, "唯一编码重复", 409);

    private final int code;
    private final String message;
    private final int httpStatus;

    WmsErrorCode(int code, String message, int httpStatus) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
    }
}
