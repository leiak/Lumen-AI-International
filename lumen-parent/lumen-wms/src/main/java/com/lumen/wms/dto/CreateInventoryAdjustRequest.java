package com.lumen.wms.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 新增库存调整单请求体.
 */
@Data
public class CreateInventoryAdjustRequest {

    @NotBlank
    @Size(max = 32)
    private String no;

    @NotNull
    private Long warehouseId;

    @NotBlank
    @Size(max = 64)
    private String reason;

    @Size(max = 500)
    private String remark;

    @NotEmpty
    private List<@Valid InventoryAdjustLineDto> lines;
}
