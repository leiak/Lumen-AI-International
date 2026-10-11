package com.lumen.wms.exception;

import com.lumen.wms.error.WmsErrorCode;

/**
 * 库存账本未找到异常.
 *
 * <p>当按 (warehouse, location, item) 精确查询某条库存行不存在时抛出，
 * 通常对应 pick/ship/inbound 等业务在错误账本上操作.
 */
public class InventoryNotFoundException extends WmsException {

    public InventoryNotFoundException(Long warehouseId, Long locationId, Long itemId) {
        super(WmsErrorCode.INVENTORY_NOT_FOUND,
                String.format("库存未找到: warehouseId=%d locationId=%d itemId=%d",
                        warehouseId, locationId, itemId));
    }
}
