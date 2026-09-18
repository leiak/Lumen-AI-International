package com.lumen.rbac.dto;

import lombok.Data;

@Data
public class UpdateRoleRequest {
    private String name;
    private String description;
    private Integer status;
}