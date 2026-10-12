package com.lumen.dispatch.exception;

import com.lumen.dispatch.error.DispatchErrorCode;

/**
 * 承运人编码唯一约束冲突 (tenant_id, code).
 *
 * <p>对应 MySQL 唯一索引 {@code uk_wms_carrier_tenant_code}.
 */
public class CarrierDuplicateCodeException extends DispatchException {

    public CarrierDuplicateCodeException(String code) {
        super(DispatchErrorCode.CARRIER_DUPLICATE_CODE,
              "承运人编码重复: " + code);
    }
}