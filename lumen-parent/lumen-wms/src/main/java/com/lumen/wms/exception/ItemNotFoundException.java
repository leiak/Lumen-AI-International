package com.lumen.wms.exception;

import com.lumen.wms.error.WmsErrorCode;

/**
 * 货品未找到异常.
 */
public class ItemNotFoundException extends WmsException {

    public ItemNotFoundException(Long id) {
        super(WmsErrorCode.ITEM_NOT_FOUND, "货品未找到: id=" + id);
    }

    public ItemNotFoundException(String sku) {
        super(WmsErrorCode.ITEM_NOT_FOUND, "货品未找到: sku=" + sku);
    }
}
