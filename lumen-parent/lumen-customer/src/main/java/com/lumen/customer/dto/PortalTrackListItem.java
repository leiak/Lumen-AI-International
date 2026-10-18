package com.lumen.customer.dto;
import java.time.LocalDateTime;
public record PortalTrackListItem(
    Long doId, String doNo, String status,
    String originAddress, String destAddress,
    String recipientName, String recipientPhone,
    LocalDateTime createdAt, LocalDateTime signedAt) {}