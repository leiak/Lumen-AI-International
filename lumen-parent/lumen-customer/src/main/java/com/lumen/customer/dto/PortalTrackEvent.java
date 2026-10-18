package com.lumen.customer.dto;
import java.time.LocalDateTime;
public record PortalTrackEvent(
    String status, String eventName, String operator,
    LocalDateTime occurredAt, String remark) {}