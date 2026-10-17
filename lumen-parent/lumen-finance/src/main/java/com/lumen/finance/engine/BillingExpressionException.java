package com.lumen.finance.engine;

import com.lumen.finance.exception.FinanceErrorCode;
import com.lumen.finance.exception.FinanceException;

/**
 * 计费表达式求值/编译异常 (Iteration 4.3.1, PR2-T2.1).
 *
 * <p>继承自 {@link FinanceException}, 由 lumen-common 的 GlobalExceptionHandler
 * 自动映射为对应 HTTP 响应. 子异常 {@link FinanceException_UnknownVar}
 * 用于精确标识未注册的具体变量名.
 */
public class BillingExpressionException extends FinanceException {

    public BillingExpressionException(FinanceErrorCode code, String message) {
        super(code, message);
    }

    public BillingExpressionException(FinanceErrorCode code, String message, Throwable cause) {
        super(code, message);
        if (cause != null) initCause(cause);
    }

    public BillingExpressionException(FinanceErrorCode code) {
        super(code);
    }

    /**
     * 默认实现 (基类) 不携带具体变量名; {@link FinanceException_UnknownVar} 重写以暴露真实变量名.
     */
    public String getVariableName() {
        return null;
    }

    /**
     * 内部子异常: 标识具体哪个变量未注册.
     *
     * <p>用于错误响应中携带变量名, 方便定位脚本错误.
     */
    public static class FinanceException_UnknownVar extends BillingExpressionException {

        private final String variableName;

        public FinanceException_UnknownVar(String variableName) {
            super(FinanceErrorCode.EXPRESSION_UNKNOWN_VARIABLE,
                  "Unknown variable: " + variableName);
            this.variableName = variableName;
        }

        @Override
        public String getVariableName() {
            return variableName;
        }
    }
}
