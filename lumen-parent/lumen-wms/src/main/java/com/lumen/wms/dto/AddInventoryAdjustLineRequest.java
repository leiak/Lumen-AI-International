package com.lumen.wms.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 库存调整单追加行请求体 — {@code lineNo} 由服务层按需自动分配.
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class AddInventoryAdjustLineRequest extends InventoryAdjustLineDto {

    private Integer lineNo;
}
