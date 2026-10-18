package com.lumen.customer.dto;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
public record PortalTrackDetail(
    Long doId, String doNo, String status,
    String originAddress, String destAddress,
    String recipientName, String recipientPhone,
    BigDecimal weight, BigDecimal volume,
    LocalDateTime createdAt, LocalDateTime signedAt,
    List<PortalTrackEvent> timeline) {}