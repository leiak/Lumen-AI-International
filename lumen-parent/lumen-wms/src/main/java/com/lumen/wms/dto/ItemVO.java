package com.lumen.wms.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 货品视图对象.
 */
@Data
public class ItemVO {

    private Long id;
    private Long tenantId;
    private String sku;
    private String name;
    private String uom;
    private String barcode;
    private String hazardClass;
    private BigDecimal unitCost;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}