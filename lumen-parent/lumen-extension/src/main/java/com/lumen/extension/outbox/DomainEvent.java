package com.lumen.extension.outbox;

import java.time.LocalDateTime;
import java.util.UUID;

public abstract class DomainEvent {
    private final String eventId = UUID.randomUUID().toString();
    private final LocalDateTime occurredAt = LocalDateTime.now();

    public abstract String eventType();
    public abstract String aggregateType();
    public abstract String aggregateId();
    public abstract Long tenantId();

    public String getEventId() { return eventId; }
    public LocalDateTime getOccurredAt() { return occurredAt; }
}