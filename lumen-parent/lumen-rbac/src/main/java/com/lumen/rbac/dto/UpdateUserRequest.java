package com.lumen.rbac.dto;

import lombok.Data;

@Data
public class UpdateUserRequest {
    private String realName;
    private String email;
    private String phone;
    private Integer status;
}