package com.subscriptionbilling.notifications.relay.kafka;

/**
 * An {@code OutboxEvent.eventType} has no known Avro schema/topic mapping in {@link
 * AvroEventMapper}. Treated by {@link KafkaEventPublisher} the same as any other publish
 * failure -- the row stays unpublished and its age keeps accruing against the relay's
 * stuck-row metrics, making a schema/producer mismatch visible via alerting rather than a
 * silently dropped event.
 */
public class UnrecognizedEventTypeException extends RuntimeException {

    UnrecognizedEventTypeException(String eventType) {
        super("No Avro schema/topic mapping for outbox event type: " + eventType);
    }
}
