package com.lumen.dispatch.exception;

import com.lumen.dispatch.error.DispatchErrorCode;

/**
 * 配送单状态非法（状态机校验失败兜底异常）.
 *
 * <p>正常情况下 {@code StateMachineEngine.assertTransition()} 抛
 * {@code StateMachineException}（来自 lumen-extension），本异常仅用于
 * 跨实体一致性检查（例如车辆状态与配送单状态矛盾、终态单据的再次操作）.
 */
public class IllegalDeliveryOrderStateException extends DispatchException {

    public IllegalDeliveryOrderStateException(String message) {
        super(DispatchErrorCode.ILLEGAL_DELIVERY_STATE, message);
    }
}
