package com.lumen.dispatch.exception;

import com.lumen.dispatch.error.DispatchErrorCode;

/**
 * 司机主数据未找到.
 */
public class DriverNotFoundException extends DispatchException {

    public DriverNotFoundException(Long id) {
        super(DispatchErrorCode.DRIVER_NOT_FOUND, "司机未找到: id=" + id);
    }
}
