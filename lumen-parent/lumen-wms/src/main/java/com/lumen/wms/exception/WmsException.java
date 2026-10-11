package com.lumen.wms.exception;

import com.lumen.common.error.BizException;
import com.lumen.common.error.CommonErrorCode;
import com.lumen.common.error.ErrorCode;
import com.lumen.wms.error.WmsErrorCode;

/**
 * WMS 模块统一业务异常基类.
 *
 * <p>所有 WMS 业务异常均继承自此类，进而被 {@code GlobalExceptionHandler}
 * 统一捕获并按 {@link WmsErrorCode} 映射 HTTP 响应.
 */
public class WmsException extends BizException {

    public WmsException(WmsErrorCode code, String message) {
        super(code, message);
    }

    public WmsException(ErrorCode code, String message) {
        super(code, message);
    }

    /**
     * 兜底构造器：当调用方只有消息文本时使用 {@link CommonErrorCode#INTERNAL_ERROR}
     * 作为错误码（建议优先使用 {@link #WmsException(WmsErrorCode, String)}）.
     */
    public WmsException(String message) {
        super(CommonErrorCode.INTERNAL_ERROR, message);
    }
}
