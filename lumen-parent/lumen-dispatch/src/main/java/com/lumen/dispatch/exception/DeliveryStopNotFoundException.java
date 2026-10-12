package com.lumen.dispatch.exception;

import com.lumen.dispatch.error.DispatchErrorCode;

/**
 * 配送站点未找到.
 *
 * <p>由 {@code DeliveryOrderService.arrive / sign} 等基于 stop_id 的
 * 操作在站点不存在时抛出.
 */
public class DeliveryStopNotFoundException extends DispatchException {

    public DeliveryStopNotFoundException(Long id) {
        super(DispatchErrorCode.DELIVERY_STOP_NOT_FOUND, "配送站点未找到: id=" + id);
    }
}
