package com.lumen.dispatch.exception;

import com.lumen.dispatch.error.DispatchErrorCode;

/**
 * 承运人主数据未找到.
 */
public class CarrierNotFoundException extends DispatchException {

    public CarrierNotFoundException(Long id) {
        super(DispatchErrorCode.CARRIER_NOT_FOUND, "承运人未找到: id=" + id);
    }
}
