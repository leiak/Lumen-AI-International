package com.lumen.wms.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 库存调整单行视图对象.
 */
@Data
public class InventoryAdjustLineVO {

    private Long id;
    private Long tenantId;
    private Long adjustId;
    private Integer lineNo;
    private Long itemId;
    private Long locationId;
    private BigDecimal deltaQty;
    private String reasonDetail;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
