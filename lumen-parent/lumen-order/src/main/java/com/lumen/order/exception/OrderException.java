package com.lumen.order.exception;

import com.lumen.common.error.BizException;
import com.lumen.common.error.ErrorCode;
import lombok.Getter;

/**
 * 货运委托单域业务异常 (Iteration 4.6).
 *
 * <p><b>继承 {@link BizException} 而不是自己造一套</b> ——
 * {@code GlobalExceptionHandler.handleBiz} 已经做对了「HTTP 状态取自 {@code ErrorCode.getHttpStatus()}、
 * body.code 取自 {@code ErrorCode.getCode()}」, 管理端与门户端都自动生效, <b>不需要新建
 * {@code @RestControllerAdvice}</b>。
 *
 * <p>门户端的 {@code CustomerExceptionHandler} 虽然是 {@code HIGHEST_PRECEDENCE}, 但它
 * {@code @RestControllerAdvice(basePackageClasses = CustomerModuleMarker.class)} 只管
 * {@code com.lumen.customer} 包下的 controller, 且只声明了
 * {@code CustomerException} / {@code FinanceException} / 参数解析三个 handler,
 * <b>没有 BizException 分支</b> —— 所以 order 域抛的本异常会落到
 * {@code GlobalExceptionHandler.handleBiz}, 客户端拿到的 code 是 order 段的码。
 * 这是刻意的 (见 spec §六·3.1)。
 *
 * <p>{@code getErrorCode()} 由父类 {@code BizException} 的 {@code @Getter} 提供, 本类不再重复生成。
 */
@Getter
public class OrderException extends BizException {

    public OrderException(ErrorCode errorCode) {
        super(errorCode);
    }

    public OrderException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }

    public static OrderException of(ErrorCode errorCode) {
        return new OrderException(errorCode);
    }
}
