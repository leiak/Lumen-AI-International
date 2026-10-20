package com.lumen.customer.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 客户取消订单请求 (Iteration 4.5B.2).
 * <p>customerId / tenantId 不在 DTO, 由 server 强制注入.
 * <p>doId 通过 path variable / request param 传, 不在 body.
 */
public record PortalCancelOrderRequest(
    @NotBlank @Size(min = 2, max = 200) String reason
) {}
