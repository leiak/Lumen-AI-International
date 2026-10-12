package com.lumen.dispatch.exception;

import com.lumen.dispatch.error.DispatchErrorCode;

/**
 * 司机手机号唯一约束冲突 (tenant_id, phone).
 *
 * <p>对应 MySQL 唯一索引 {@code uk_wms_driver_tenant_phone}.
 */
public class DriverDuplicatePhoneException extends DispatchException {

    public DriverDuplicatePhoneException(String phone) {
        super(DispatchErrorCode.DRIVER_DUPLICATE_PHONE,
              "司机手机号重复: " + phone);
    }
}