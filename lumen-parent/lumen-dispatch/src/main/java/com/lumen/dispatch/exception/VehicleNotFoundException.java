package com.lumen.dispatch.exception;

import com.lumen.dispatch.error.DispatchErrorCode;

/**
 * 车辆主数据未找到.
 *
 * <p>由 {@code VehicleService.get / update / delete / freeze / unfreeze}
 * 在 {@code mapper.selectById(id)} 返回 null 时抛出.
 */
public class VehicleNotFoundException extends DispatchException {

    public VehicleNotFoundException(Long id) {
        super(DispatchErrorCode.VEHICLE_NOT_FOUND, "车辆未找到: id=" + id);
    }
}
