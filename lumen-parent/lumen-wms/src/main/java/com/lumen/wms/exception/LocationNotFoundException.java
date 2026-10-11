package com.lumen.wms.exception;

import com.lumen.wms.error.WmsErrorCode;

/**
 * 库位未找到异常.
 */
public class LocationNotFoundException extends WmsException {

    public LocationNotFoundException(Long id) {
        super(WmsErrorCode.LOCATION_NOT_FOUND, "库位未找到: id=" + id);
    }

    public LocationNotFoundException(Long warehouseId, String code) {
        super(WmsErrorCode.LOCATION_NOT_FOUND,
                "库位未找到: warehouseId=" + warehouseId + ", code=" + code);
    }
}
