package com.lumen.wms.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 更新库位请求体.
 */
@Data
public class UpdateLocationRequest {

    private Long warehouseId;

    @Size(max = 64)
    private String code;

    @Size(max = 32)
    private String zone;

    @DecimalMin(value = "0.0", inclusive = true)
    private BigDecimal capacity;

    @Size(max = 16)
    private String status;
}