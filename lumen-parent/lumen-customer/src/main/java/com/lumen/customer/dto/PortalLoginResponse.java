package com.lumen.customer.dto;
import java.util.List;
public record PortalLoginResponse(
    String token,
    Long activeCustomerId,
    List<PortalCustomerChoice> choices,  // null 若单客户
    Long userId,
    String username,
    String realName) {}