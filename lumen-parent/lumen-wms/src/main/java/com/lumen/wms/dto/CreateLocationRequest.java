package com.lumen.wms.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 新增库位请求体.
 */
@Data
public class CreateLocationRequest {

    @NotNull
    private Long warehouseId;

    @NotBlank
    @Size(max = 64)
    private String code;

    @Size(max = 32)
    private String zone;

    @DecimalMin(value = "0.0", inclusive = true)
    private BigDecimal capacity;

    @Size(max = 16)
    private String status;
}