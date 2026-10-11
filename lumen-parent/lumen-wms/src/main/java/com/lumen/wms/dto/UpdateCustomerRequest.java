package com.lumen.wms.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 更新客户请求体.
 */
@Data
public class UpdateCustomerRequest {

    @Size(max = 64)
    private String code;

    @Size(max = 128)
    private String name;

    @Size(max = 64)
    private String contact;

    @Size(max = 32)
    private String phone;

    @Size(max = 256)
    private String address;
}