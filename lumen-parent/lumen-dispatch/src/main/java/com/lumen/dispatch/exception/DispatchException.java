package com.lumen.dispatch.exception;

import com.lumen.common.error.BizException;
import com.lumen.common.error.CommonErrorCode;
import com.lumen.common.error.ErrorCode;
import com.lumen.dispatch.error.DispatchErrorCode;

/**
 * Dispatch 模块统一业务异常基类.
 *
 * <p>所有 Dispatch 业务异常均继承自此类，进而被 {@code GlobalExceptionHandler}
 * 统一捕获并按 {@link DispatchErrorCode} 映射 HTTP 响应.
 */
public class DispatchException extends BizException {

    public DispatchException(DispatchErrorCode code) {
        super(code, code.getMessage());
    }

    public DispatchException(DispatchErrorCode code, String message) {
        super(code, message);
    }

    /**
     * 兜底构造器：当调用方只有通用 {@link ErrorCode}（{@link com.lumen.common.error.CommonErrorCode}
     * 或其他模块枚举）时使用. 错误码本身需要是 dispatch 域内时优先调用上方两构造器.
     */
    public DispatchException(ErrorCode code, String message) {
        super(code, message);
    }

    /**
     * 兜底构造器：当调用方只有消息文本时使用 {@link CommonErrorCode#INTERNAL_ERROR}
     * 作为错误码（建议优先使用 {@link #DispatchException(DispatchErrorCode, String)}）.
     */
    public DispatchException(String message) {
        super(CommonErrorCode.INTERNAL_ERROR, message);
    }
}