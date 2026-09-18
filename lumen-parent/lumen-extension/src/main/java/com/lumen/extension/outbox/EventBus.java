package com.lumen.extension.outbox;

public interface EventBus {
    void publish(DomainEvent event);
}