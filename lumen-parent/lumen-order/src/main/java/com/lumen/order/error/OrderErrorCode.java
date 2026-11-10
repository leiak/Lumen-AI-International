package com.lumen.order.error;

import com.lumen.common.error.ErrorCode;
import lombok.Getter;

/**
 * 货运委托单域错误码 (Iteration 4.6).
 *
 * <p><b>风格沿用 finance / portal 的「数字前缀 + _ + 三位序号」</b> (如 {@code 404_001} / {@code 400_001} / {@code 409_003}),
 * 设计文档里的 {@code ERR_<DOMAIN>_<CODE>} 那一套在本仓没有先例, 不用。
 *
 * <p><b>号段取 {@code 4xx_1xx}</b> —— finance 占了 404_001..404_007 / 400_001..400_023 /
 * 409_001..409_005, portal 占了 401_003 / 403_001..403_003 / 404_001..404_005 /
 * 409_001..409_002 / 400_001..400_005 / 500_001, order 另起 {@code 1xx} 段互不撞号。
 *
 * <p><b>枚举参数顺序是 {@code (code, message, httpStatus)}</b> —— 照 {@code FinanceErrorCode}。
 * 注意 {@code PortalErrorCode} 的顺序**正好相反** ({@code (code, httpStatus, message)}),
 * 照抄错一个编译器不报错、运行时 httpStatus 全错。
 *
 * <p><b>字段级校验不进本枚举</b> —— {@code @NotNull} / {@code @NotBlank} / {@code @Positive} 这类
 * 单字段约束由 jakarta bean validation 兜住, 走 {@code GlobalExceptionHandler.handleValidation}
 * → HTTP 400 + {@code CommonErrorCode.PARAM_INVALID} (code 4001)。
 * 本枚举只收**跨字段 / 集合级 / 状态级**的规则。
 */
@Getter
public enum OrderErrorCode implements ErrorCode {

    ORDER_NOT_FOUND(404_101, "委托单不存在", 404),
    CUSTOMER_NOT_FOUND(404_102, "客户不存在", 404),

    STATE_TRANSITION_INVALID(409_101, "当前状态不允许该操作", 409),
    ORDER_NOT_EDITABLE(409_102, "当前状态不可编辑", 409),

    CARGO_EMPTY(400_101, "货物明细不能为空", 400),
    SERVICE_ITEM_EMPTY(400_102, "服务项不能为空", 400),
    REASON_REQUIRED(400_103, "原因不能为空", 400),
    AMOUNT_INVALID(400_104, "服务项金额合计必须大于 0", 400);

    private final int code;
    private final String message;
    private final int httpStatus;

    OrderErrorCode(int code, String message, int httpStatus) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
    }
}
