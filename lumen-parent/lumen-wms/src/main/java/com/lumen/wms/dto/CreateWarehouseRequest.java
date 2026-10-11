package com.lumen.wms.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 新增仓库请求体.
 */
@Data
public class CreateWarehouseRequest {

    @NotBlank
    @Size(max = 64)
    private String code;

    @NotBlank
    @Size(max = 128)
    private String name;

    @Size(max = 32)
    private String type;

    @Size(max = 16)
    private String status;

    @Size(max = 256)
    private String address;
}