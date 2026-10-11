package com.lumen.wms.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 入库单行视图对象.
 */
@Data
public class InboundLineVO {

    private Long id;
    private Long tenantId;
    private Long noteId;
    private Integer lineNo;
    private Long itemId;
    private BigDecimal qty;
    private BigDecimal receivedQty;
    private Long putawayLocationId;
    private BigDecimal putawayQty;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
