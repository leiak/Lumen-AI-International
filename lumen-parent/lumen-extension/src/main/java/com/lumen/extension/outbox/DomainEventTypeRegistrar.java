package com.lumen.extension.outbox;

import java.util.Map;

/**
 * SPI for downstream modules (e.g. lumen-dispatch, lumen-wms) to register
 * their {@link DomainEvent} subtypes with {@link OutboxDispatcher}, so the
 * dispatcher can deserialize outbox rows back into the correct concrete class
 * for Spring {@code ApplicationEventPublisher} re-publishing.
 *
 * <p><b>Why this exists:</b> the outbox table stores events as JSON payload
 * plus an {@code event_type} string. The dispatcher must know the target
 * class to deserialize into (Jackson cannot recover the class from JSON
 * without a polymorphic hint). Downstream modules own their event classes,
 * so lumen-extension cannot hardcode a switch statement per module — instead,
 * each module contributes a registrar bean at startup.
 *
 * <p><b>Contract:</b>
 * <ul>
 *   <li>Implementations must be Spring beans (annotated {@code @Component}
 *       or equivalent) and live in a module that already depends on
 *       lumen-extension.</li>
 *   <li>The returned map keys are the canonical {@code event_type} strings
 *       (matching {@link DomainEvent#eventType()}). Values are the deserialization
 *       target classes.</li>
 *   <li>The map is read once per outbox batch by {@link OutboxDispatcher};
 *       implementations may return an immutable map.</li>
 *   <li>If two registrars declare the same key, the last-registered wins
 *       (intentional; downstream modules override upstream stubs).</li>
 * </ul>
 *
 * <p><b>What this is NOT:</b> event types NOT in any registrar still throw
 * {@link IllegalArgumentException} from {@link OutboxDispatcher#parseEvent},
 * which is the contract the {@code OutboxRetryIT.poisonMessage_unknownEventType
 * _goesDeadLetterAfterMaxRetries} test asserts. Truly unknown event types
 * remain poison messages and go to {@code DEAD_LETTER} after retries.
 *
 * @see OutboxDispatcher#parseEvent
 * @since Iteration 4.1.x — fix for "delivery_order.state_changed Unknown
 *      event type" retry storm when lumen-dispatch began publishing outbox
 *      events without writing a listener (per design §4.6).
 */
public interface DomainEventTypeRegistrar {

    /**
     * @return immutable map of {@code event_type → DomainEvent subtype} for
     *         this module. Keys must equal the canonical
     *         {@link DomainEvent#eventType()} of the corresponding class.
     */
    Map<String, Class<? extends DomainEvent>> registeredTypes();
}