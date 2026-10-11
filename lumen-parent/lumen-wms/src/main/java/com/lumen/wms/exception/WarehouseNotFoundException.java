package com.lumen.wms.exception;

import com.lumen.wms.error.WmsErrorCode;

/**
 * 仓库未找到异常.
 */
public class WarehouseNotFoundException extends WmsException {

    public WarehouseNotFoundException(Long id) {
        super(WmsErrorCode.WAREHOUSE_NOT_FOUND, "仓库未找到: id=" + id);
    }

    public WarehouseNotFoundException(String code) {
        super(WmsErrorCode.WAREHOUSE_NOT_FOUND, "仓库未找到: code=" + code);
    }
}
