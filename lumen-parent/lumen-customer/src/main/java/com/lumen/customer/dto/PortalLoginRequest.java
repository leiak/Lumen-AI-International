package com.lumen.customer.dto;
import jakarta.validation.constraints.NotBlank;
public record PortalLoginRequest(
    @NotBlank String username,
    @NotBlank String password) {}