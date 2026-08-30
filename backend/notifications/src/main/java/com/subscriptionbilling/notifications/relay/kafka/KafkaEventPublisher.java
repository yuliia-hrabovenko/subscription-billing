package com.subscriptionbilling.notifications.relay.kafka;

import com.subscriptionbilling.notifications.outbox.OutboxEvent;
import com.subscriptionbilling.notifications.relay.EventPublishException;
import com.subscriptionbilling.notifications.relay.EventPublisher;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * {@link EventPublisher} adapter to Kafka: resolves the outbox event's topic and Avro
 * record via {@link AvroEventMapper}, then sends it keyed by the outbox row id (so a
 * downstream consumer can deduplicate a redelivery -- this module makes no
 * exactly-once/ordering guarantee beyond what Kafka's per-key partitioning already
 * provides). The send is awaited synchronously up to {@link #SEND_TIMEOUT} so {@link
 * com.subscriptionbilling.notifications.relay.OutboxRelay} can treat one row's publish
 * failure as isolated from the rest of its poll batch, rather than the whole batch
 * failing together under one async callback.
 */
@Component
public class KafkaEventPublisher implements EventPublisher {

    private static final Duration SEND_TIMEOUT = Duration.ofSeconds(10);

    private final KafkaTemplate<Object, Object> kafkaTemplate;
    private final AvroEventMapper avroEventMapper;

    public KafkaEventPublisher(KafkaTemplate<Object, Object> kafkaTemplate, AvroEventMapper avroEventMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.avroEventMapper = avroEventMapper;
    }

    @Override
    public void publish(OutboxEvent event) {
        MappedEvent mapped = avroEventMapper.toAvro(event);
        try {
            kafkaTemplate.send(mapped.topic(), event.getId().toString(), mapped.value())
                    .get(SEND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new EventPublishException("Interrupted publishing outbox event " + event.getId(), interrupted);
        } catch (ExecutionException | TimeoutException sendFailed) {
            throw new EventPublishException(
                    "Failed to publish outbox event " + event.getId() + " to topic " + mapped.topic(), sendFailed);
        }
    }
}
