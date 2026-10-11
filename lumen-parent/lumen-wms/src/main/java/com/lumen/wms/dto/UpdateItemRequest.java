package com.lumen.wms.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 更新货品请求体.
 */
@Data
public class UpdateItemRequest {

    @Size(max = 64)
    private String sku;

    @Size(max = 128)
    private String name;

    @Size(max = 16)
    private String uom;

    @Size(max = 64)
    private String barcode;

    @Size(max = 32)
    private String hazardClass;

    @DecimalMin(value = "0.0", inclusive = true)
    private BigDecimal unitCost;
}