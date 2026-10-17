package com.lumen.finance.exception;

import com.lumen.common.error.BizException;

/**
 * Finance 模块业务异常 (Iteration 4.3.1).
 *
 * <p>由 lumen-common 的 {@code GlobalExceptionHandler} 捕获并映射为对应 HTTP 响应.
 */
public class FinanceException extends BizException {
    public FinanceException(FinanceErrorCode code) { super(code); }
    public FinanceException(FinanceErrorCode code, String message) { super(code, message); }
}
