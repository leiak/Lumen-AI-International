package com.lumen.wms.exception;

import com.lumen.wms.error.WmsErrorCode;

/**
 * 库存调整单未找到异常.
 */
public class InventoryAdjustNotFoundException extends WmsException {

    public InventoryAdjustNotFoundException(Long id) {
        super(WmsErrorCode.INVENTORY_ADJUST_NOT_FOUND, "库存调整单未找到: id=" + id);
    }

    public InventoryAdjustNotFoundException(String no) {
        super(WmsErrorCode.INVENTORY_ADJUST_NOT_FOUND, "库存调整单未找到: no=" + no);
    }
}
