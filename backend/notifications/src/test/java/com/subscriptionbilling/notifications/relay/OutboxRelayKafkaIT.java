package com.subscriptionbilling.notifications.relay;

import com.subscriptionbilling.notifications.NotificationsTestApplication;
import com.subscriptionbilling.notifications.outbox.OutboxEvent;
import com.subscriptionbilling.notifications.outbox.OutboxEventRepository;
import com.subscriptionbilling.notifications.relay.avro.PaymentSucceededEvent;
import com.subscriptionbilling.notifications.support.AbstractKafkaIntegrationTest;
import io.apicurio.registry.serde.avro.AvroKafkaDeserializer;
import io.apicurio.registry.serde.avro.AvroSerdeConfig;
import io.apicurio.registry.serde.config.SerdeConfig;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the outbox-to-Kafka boundary this module owns: a row is published in the
 * correct Avro schema, keyed by its outbox id, on the topic its
 * event type designates. Deliberately does not re-assert any of the four trigger
 * conditions -- those are covered where each trigger point lives (e.g. {@code
 * SubscriptionServiceIT}).
 *
 * <p>The row here is seeded directly via the repository, with no triggering transaction
 * of its own in this test process -- structurally the same starting state a crash between
 * a trigger's commit and the relay's next poll would leave behind, which is what proves
 * the crash-survival property without literally crashing the process.
 */
@SpringBootTest(classes = NotificationsTestApplication.class)
@Import(OutboxRelayKafkaIT.MetricsTestConfig.class)
class OutboxRelayKafkaIT extends AbstractKafkaIntegrationTest {

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private OutboxRelay outboxRelay;

    @Test
    void aSeededRowIsPublishedToKafkaInTheCorrectAvroSchemaAndMarkedPublished() {
        UUID eventId = UUID.randomUUID();
        OutboxEvent event = outboxEventRepository.saveAndFlush(new OutboxEvent(eventId, "PAYMENT_SUCCEEDED",
                "{\"invoiceId\":\"inv-1\",\"subscriptionId\":\"sub-1\",\"billingPeriod\":\"2026-08\"}"));

        int publishedCount = outboxRelay.pollAndPublish();

        assertThat(publishedCount).isOne();
        assertThat(outboxEventRepository.findById(event.getId()).orElseThrow().getPublishedAt()).isNotNull();

        try (KafkaConsumer<String, PaymentSucceededEvent> consumer = avroConsumer()) {
            consumer.subscribe(List.of("notifications.payment-succeeded"));
            ConsumerRecords<String, PaymentSucceededEvent> records = consumer.poll(Duration.ofSeconds(15));

            assertThat(records.count()).isOne();
            ConsumerRecord<String, PaymentSucceededEvent> record = records.iterator().next();
            assertThat(record.key()).isEqualTo(eventId.toString());
            PaymentSucceededEvent value = record.value();
            assertThat(value.getEventId()).isEqualTo(eventId.toString());
            assertThat(value.getInvoiceId()).isEqualTo("inv-1");
            assertThat(value.getSubscriptionId()).isEqualTo("sub-1");
            assertThat(value.getBillingPeriod()).isEqualTo("2026-08");
        }
    }

    private KafkaConsumer<String, PaymentSucceededEvent> avroConsumer() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "outbox-relay-kafka-it-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, AvroKafkaDeserializer.class.getName());
        props.put(SerdeConfig.REGISTRY_URL, schemaRegistryUrl());
        props.put(AvroSerdeConfig.USE_SPECIFIC_AVRO_READER, "true");
        return new KafkaConsumer<>(props);
    }

    /**
     * Production supplies a {@link MeterRegistry} bean via the {@code api} module's
     * actuator/Prometheus dependency, not this library module's own -- this test-only
     * substitute exists so {@link OutboxRelayMetrics} can be constructed by this context.
     */
    @TestConfiguration
    static class MetricsTestConfig {

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }
}
