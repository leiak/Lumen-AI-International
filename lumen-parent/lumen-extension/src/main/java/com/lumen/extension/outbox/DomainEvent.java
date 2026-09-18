package com.lumen.extension.outbox;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.LocalDateTime;
import java.util.UUID;

public abstract class DomainEvent {
    private final String eventId = UUID.randomUUID().toString();

    /**
     * @JsonIgnore：occurredAt 在构造时由 {@link LocalDateTime#now()} 赋值，
     * 反序列化时不从 payload 还原（payload 里的 occurredAt 是 Hutool 序列化成
     * 的 epoch millis，Jackson 不开 JSR310 + 偏移量上下文就解析不出来）。
     * 重新发布事件时该字段由 JVM 重新生成。
     */
    @JsonIgnore
    private final LocalDateTime occurredAt = LocalDateTime.now();

    public abstract String eventType();
    public abstract String aggregateType();
    public abstract String aggregateId();
    public abstract Long tenantId();

    public String getEventId() { return eventId; }
    public LocalDateTime getOccurredAt() { return occurredAt; }
}