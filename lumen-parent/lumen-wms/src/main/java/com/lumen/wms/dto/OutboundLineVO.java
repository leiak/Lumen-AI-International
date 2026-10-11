package com.lumen.wms.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 出库单行视图对象.
 */
@Data
public class OutboundLineVO {

    private Long id;
    private Long tenantId;
    private Long noteId;
    private Integer lineNo;
    private Long itemId;
    private BigDecimal qty;
    private BigDecimal pickedQty;
    private Long sourceLocationId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
