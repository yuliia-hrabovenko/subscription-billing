package com.subscriptionbilling.notifications.relay.kafka;

import org.apache.avro.specific.SpecificRecordBase;

/**
 * An {@code OutboxEvent} translated into the Kafka topic and Avro record it publishes as.
 */
public record MappedEvent(String topic, SpecificRecordBase value) {
}
