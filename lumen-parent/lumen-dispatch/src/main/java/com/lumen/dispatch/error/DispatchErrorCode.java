package com.lumen.dispatch.error;

import com.lumen.common.error.ErrorCode;
import lombok.Getter;

/**
 * Dispatch 模块业务错误码.
 *
 * <p>遵循 lumen-common 约定（见 {@link ErrorCode}），由 {@code GlobalExceptionHandler}
 * 捕获 {@link com.lumen.dispatch.exception.DispatchException} 并映射为对应 HTTP 状态码.
 *
 * <p>错误码区间：60001-60099（Dispatch 业务域）.
 */
@Getter
public enum DispatchErrorCode implements ErrorCode {
    VEHICLE_NOT_FOUND          (60001, "车辆不存在",          404),
    DRIVER_NOT_FOUND           (60002, "司机不存在",          404),
    CARRIER_NOT_FOUND          (60003, "承运人不存在",        404),
    DELIVERY_ORDER_NOT_FOUND   (60004, "配送单不存在",        404),
    EXCEPTION_NOT_FOUND        (60005, "异常工单不存在",      404),
    INSUFFICIENT_VEHICLE       (60006, "车辆容量不足",        409),
    VEHICLE_NOT_AVAILABLE      (60007, "车辆不可用",          409),
    DRIVER_NOT_AVAILABLE       (60008, "司机不可用",          409),
    ILLEGAL_DELIVERY_STATE     (60009, "配送单状态非法",      409),
    ILLEGAL_VEHICLE_STATE      (60010, "车辆状态非法",        409),
    ILLEGAL_EXCEPTION_STATE    (60011, "异常工单状态非法",    409),
    DELIVERY_DUPLICATE_NO      (60012, "配送单号重复",        409),
    VEHICLE_DUPLICATE_PLATE    (60013, "车牌号重复",          409),
    DRIVER_DUPLICATE_PHONE     (60014, "司机手机号重复",      409),
    CARRIER_DUPLICATE_CODE     (60015, "承运人编码重复",      409),
    EXCEPTION_ORDER_REQUIRED   (60016, "必须先建异常工单",    409),
    SIGN_PHOTO_REQUIRED        (60017, "签收必须上传照片",    400),
    NO_AVAILABLE_DRIVER        (60018, "无可用司机",          409),
    NO_AVAILABLE_VEHICLE       (60019, "无可用车辆",          409);

    private final int code;
    private final String message;
    private final int httpStatus;

    DispatchErrorCode(int code, String message, int httpStatus) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
    }
}
