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
    WAREHOUSE_NOT_FOUND         (50001, "仓库未找到", 404),
    LOCATION_NOT_FOUND          (50002, "库位未找到", 404),
    ITEM_NOT_FOUND              (50003, "货品未找到", 404),
    CUSTOMER_NOT_FOUND          (50004, "客户未找到", 404),
    DUPLICATE_CODE              (50005, "唯一编码重复", 409),
    INVENTORY_NOT_FOUND         (50006, "库存未找到", 404),
    INSUFFICIENT_INVENTORY      (50007, "库存不足", 409),
    INBOUND_NOT_FOUND           (50008, "入库单未找到", 404),
    INBOUND_LINE_NOT_FOUND      (50009, "入库单行未找到", 404),
    INBOUND_DUPLICATE_NO        (50010, "入库单号重复", 409),
    INVENTORY_ADJUST_NOT_FOUND  (50011, "库存调整单未找到", 404),
    INVENTORY_ADJUST_LINE_NOT_FOUND (50012, "库存调整单行未找到", 404),
    INVENTORY_ADJUST_DUPLICATE_NO(50013, "库存调整单号重复", 409),
    THRESHOLD_CONFIG_NOT_FOUND  (50014, "阈值配置未找到", 404);

    private final int code;
    private final String message;
    private final int httpStatus;

    WmsErrorCode(int code, String message, int httpStatus) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
    }
}
