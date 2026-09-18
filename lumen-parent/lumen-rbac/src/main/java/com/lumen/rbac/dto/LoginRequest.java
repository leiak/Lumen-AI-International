package com.lumen.rbac.dto;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
@Data public class LoginRequest {
    @NotBlank private String username;
    @NotBlank private String password;
    private Long tenantId;
}
