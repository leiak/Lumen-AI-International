package com.lumen.dispatch.exception;

import com.lumen.dispatch.error.DispatchErrorCode;

/**
 * 车辆容量不足.
 *
 * <p>由 {@code LoadingValidator.validate(vehicle, order)} 在重量/体积/站点数
 * 超出车辆承载范围时抛出，对应 HTTP 409 Conflict.
 */
public class InsufficientVehicleException extends DispatchException {

    public InsufficientVehicleException(String message) {
        super(DispatchErrorCode.INSUFFICIENT_VEHICLE, message);
    }
}
