package com.lumen.dispatch.exception;

import com.lumen.dispatch.error.DispatchErrorCode;

/**
 * 车辆车牌号唯一约束冲突 (tenant_id, plate_no).
 *
 * <p>对应 MySQL 唯一索引 {@code uk_wms_vehicle_tenant_plate}.
 */
public class VehicleDuplicatePlateException extends DispatchException {

    public VehicleDuplicatePlateException(String plateNo) {
        super(DispatchErrorCode.VEHICLE_DUPLICATE_PLATE,
              "车牌号重复: " + plateNo);
    }
}