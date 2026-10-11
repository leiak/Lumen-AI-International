package com.lumen.wms.exception;

import com.lumen.wms.error.WmsErrorCode;

import java.math.BigDecimal;

/**
 * 库存不足异常.
 *
 * <p>出库拣货 (subtractQty) / 锁定 (lockQty) / 调整 (adjustDelta)
 * 等操作因可用数量不足被拒绝时抛出.
 */
public class InsufficientInventoryException extends WmsException {

    public InsufficientInventoryException(Long warehouseId, Long locationId, Long itemId,
                                          BigDecimal requested, BigDecimal available) {
        super(WmsErrorCode.INSUFFICIENT_INVENTORY,
                String.format("库存不足: warehouseId=%d locationId=%d itemId=%d requested=%s available=%s",
                        warehouseId, locationId, itemId, requested, available));
    }

    public InsufficientInventoryException(Long warehouseId, Long locationId, Long itemId) {
        super(WmsErrorCode.INSUFFICIENT_INVENTORY,
                String.format("库存不足: warehouseId=%d locationId=%d itemId=%d",
                        warehouseId, locationId, itemId));
    }
}
