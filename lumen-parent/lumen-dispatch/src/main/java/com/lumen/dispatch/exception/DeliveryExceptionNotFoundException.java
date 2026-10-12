package com.lumen.dispatch.exception;

import com.lumen.dispatch.error.DispatchErrorCode;

/**
 * 配送异常工单未找到.
 *
 * <p>由 {@code DeliveryExceptionService.get / investigate / resolve / close}
 * 在 {@code mapper.selectById(id)} 返回 null 时抛出.
 */
public class DeliveryExceptionNotFoundException extends DispatchException {

    public DeliveryExceptionNotFoundException(Long id) {
        super(DispatchErrorCode.EXCEPTION_NOT_FOUND, "异常工单未找到: id=" + id);
    }
}
