package com.lumen.dispatch.exception;

import com.lumen.dispatch.error.DispatchErrorCode;

/**
 * 配送单未找到.
 *
 * <p>由 {@code DeliveryOrderService.get / update / delete} 在
 * {@code mapper.selectById(id)} 返回 null 时抛出.
 */
public class DeliveryOrderNotFoundException extends DispatchException {

    public DeliveryOrderNotFoundException(Long id) {
        super(DispatchErrorCode.DELIVERY_ORDER_NOT_FOUND, "配送单未找到: id=" + id);
    }
}
