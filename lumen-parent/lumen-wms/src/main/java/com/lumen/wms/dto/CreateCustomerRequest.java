package com.lumen.wms.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 新增客户请求体.
 */
@Data
public class CreateCustomerRequest {

    @NotBlank
    @Size(max = 64)
    private String code;

    @NotBlank
    @Size(max = 128)
    private String name;

    @Size(max = 64)
    private String contact;

    @Size(max = 32)
    private String phone;

    @Size(max = 256)
    private String address;
}