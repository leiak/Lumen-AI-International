package com.lumen.rbac.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateRoleRequest {
    @NotBlank
    @Size(min = 2, max = 64)
    private String code;

    @NotBlank
    @Size(min = 2, max = 64)
    private String name;

    private String description;
    private Integer status;
}