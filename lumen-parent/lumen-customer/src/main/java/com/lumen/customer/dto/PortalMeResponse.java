package com.lumen.customer.dto;
import java.util.List;
public record PortalMeResponse(
    Long userId,
    String username,
    String realName,
    Long activeCustomerId,
    String activeCustomerName,
    List<PortalCustomerChoice> allChoices) {}