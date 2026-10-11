package com.lumen.wms.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 库位视图对象.
 */
@Data
public class LocationVO {

    private Long id;
    private Long tenantId;
    private Long warehouseId;
    private String code;
    private String zone;
    private BigDecimal capacity;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}