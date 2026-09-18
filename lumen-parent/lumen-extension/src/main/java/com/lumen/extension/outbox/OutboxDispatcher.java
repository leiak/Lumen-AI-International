package com.lumen.extension.outbox;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lumen.extension.outbox.events.CountryEditApprovedEvent;
import com.lumen.extension.outbox.events.CountryEditRejectedEvent;
import com.lumen.extension.outbox.events.CountryEditSubmittedEvent;
import com.lumen.extension.outbox.events.CountryStateChangedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Polls the outbox table, claims pending rows via {@code FOR UPDATE SKIP LOCKED},
 * deserializes the JSON payload back into a {@link DomainEvent} subclass, and
 * re-publishes via Spring's {@link ApplicationEventPublisher} so that async
 * {@code @EventListener} consumers (in Tasks 7+) can react.
 *
 * <p><b>Status transitions:</b>
 * <ul>
 *   <li>Success → {@code DONE}</li>
 *   <li>Failure before max retries → {@code PENDING} with backoff
 *       (1→30s, 2→120s, ≥3→600s) on {@code nextRetryAt}</li>
 *   <li>Failure at max retries → {@code DEAD_LETTER}</li>
 * </ul>
 *
 * <p><b>JSON deserialization:</b> uses Jackson (not Hutool) because the
 * event classes are immutable — Lombok {@code @Getter} only, no setters,
 * all fields {@code private final}. Hutool's {@code JSONUtil.toBean} relies
 * on JavaBean setter convention and would produce events with all-null
 * fields. Jackson with {@code Visibility.ANY} on fields sets values via
 * reflection on the {@code final} fields directly.
 *
 * <p><b>Retry semantics:</b> a row is attempted up to {@code maxRetries}
 * TOTAL times (initial attempt + ({@code maxRetries}-1) retries). After the
 * {@code maxRetries}th failure the row is moved to {@code DEAD_LETTER}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxDispatcher {

    /**
     * Local Jackson mapper tuned for the outbox event classes. Field visibility
     * is widened to {@code ANY} so private final fields can be populated without
     * requiring setters or {@code -parameters} compiler flag.
     */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY)
            .setVisibility(PropertyAccessor.GETTER, JsonAutoDetect.Visibility.NONE)
            .setVisibility(PropertyAccessor.IS_GETTER, JsonAutoDetect.Visibility.NONE)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final EventOutboxMapper outboxMapper;
    private final ApplicationEventPublisher publisher;
    private final OutboxProperties props;

    @Scheduled(fixedDelayString = "${lumen.outbox.poll-interval-ms:2000}")
    @Transactional
    public void dispatch() {
        List<EventOutbox> batch = outboxMapper.lockPendingBatch(props.getBatchSize());
        if (batch.isEmpty()) return;

        for (EventOutbox row : batch) {
            try {
                DomainEvent event = parseEvent(row);
                publisher.publishEvent(event);
                row.setStatus(OutboxStatus.DONE.name());
                row.setProcessedAt(LocalDateTime.now());
            } catch (Exception ex) {
                log.warn("Outbox dispatch failed: eventId={} retryCount={}",
                        row.getEventId(), row.getRetryCount(), ex);
                row.setRetryCount(row.getRetryCount() + 1);
                row.setLastError(truncate(ex.getMessage(), 1000));
                if (row.getRetryCount() >= row.getMaxRetries()) {
                    row.setStatus(OutboxStatus.DEAD_LETTER.name());
                } else {
                    row.setStatus(OutboxStatus.PENDING.name());
                    row.setNextRetryAt(LocalDateTime.now().plusSeconds(backoffSeconds(row.getRetryCount())));
                }
            }
            try {
                outboxMapper.updateById(row);
            } catch (Exception updateEx) {
                log.error("Failed to update outbox row {} status", row.getId(), updateEx);
            }
        }
    }

    private DomainEvent parseEvent(EventOutbox row) throws Exception {
        String type = row.getEventType();
        return switch (type) {
            case "country.state_changed"   -> MAPPER.readValue(row.getPayload(), CountryStateChangedEvent.class);
            case "country.edit_submitted"  -> MAPPER.readValue(row.getPayload(), CountryEditSubmittedEvent.class);
            case "country.edit_approved"   -> MAPPER.readValue(row.getPayload(), CountryEditApprovedEvent.class);
            case "country.edit_rejected"   -> MAPPER.readValue(row.getPayload(), CountryEditRejectedEvent.class);
            default -> throw new IllegalArgumentException("Unknown event type: " + type);
        };
    }

    private long backoffSeconds(int retryCount) {
        return switch (retryCount) {
            case 1 -> 30L;
            case 2 -> 120L;
            default -> 600L;
        };
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}