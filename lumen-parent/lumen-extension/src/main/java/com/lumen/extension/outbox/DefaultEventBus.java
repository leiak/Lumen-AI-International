package com.lumen.extension.outbox;

import cn.hutool.json.JSONUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@RequiredArgsConstructor
public class DefaultEventBus implements EventBus {
    private final EventOutboxMapper outboxMapper;
    private final ApplicationEventPublisher publisher;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(DomainEvent event) {
        EventOutbox row = new EventOutbox();
        row.setEventId(event.getEventId());
        row.setEventType(event.eventType());
        row.setAggregateType(event.aggregateType());
        row.setAggregateId(event.aggregateId());
        row.setTenantId(event.tenantId());
        row.setPayload(JSONUtil.toJsonStr(event));
        row.setStatus(OutboxStatus.PENDING.name());
        row.setNextRetryAt(event.getOccurredAt());   // immediate dispatch
        row.setRetryCount(0);
        row.setMaxRetries(3);
        outboxMapper.insert(row);
        log.debug("Outbox row written: eventId={} type={}", event.getEventId(), event.eventType());

        // Same-module synchronous consumption (if any)
        publisher.publishEvent(event);
    }
}