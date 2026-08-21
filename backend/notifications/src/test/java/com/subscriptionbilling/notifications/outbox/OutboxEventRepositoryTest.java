package com.subscriptionbilling.notifications.outbox;

import com.subscriptionbilling.notifications.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class OutboxEventRepositoryTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Test
    void savesAndFetchesAnUnpublishedOutboxEvent() {
        OutboxEvent event = new OutboxEvent(
                UUID.randomUUID(), "PAYMENT_SUCCEEDED", "{\"invoiceId\":\"" + UUID.randomUUID() + "\"}");

        outboxEventRepository.saveAndFlush(event);

        Optional<OutboxEvent> found = outboxEventRepository.findById(event.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getEventType()).isEqualTo("PAYMENT_SUCCEEDED");
        assertThat(found.get().getPayload()).contains("invoiceId");
        assertThat(found.get().getCreatedAt()).isNotNull();
        assertThat(found.get().getPublishedAt()).isNull();
    }

    @Test
    void distinguishesAPublishedEventFromAnUnpublishedOne() {
        OutboxEvent unpublished = new OutboxEvent(UUID.randomUUID(), "TRIAL_ENDING_SOON", "{}");
        OutboxEvent published = new OutboxEvent(UUID.randomUUID(), "CANCELLATION_CONFIRMED", "{}");
        published.markPublished(Instant.now());

        outboxEventRepository.saveAndFlush(unpublished);
        outboxEventRepository.saveAndFlush(published);

        assertThat(outboxEventRepository.findById(unpublished.getId()).orElseThrow().getPublishedAt()).isNull();
        assertThat(outboxEventRepository.findById(published.getId()).orElseThrow().getPublishedAt()).isNotNull();
    }

    @Test
    void storesAnArbitraryJsonPayloadWithoutASchemaChange() {
        String payload = "{\"customerId\":\"" + UUID.randomUUID()
                + "\",\"amount\":\"19.99\",\"nested\":{\"retryCount\":2,\"tags\":[\"dunning\",\"final-attempt\"]}}";

        OutboxEvent event = new OutboxEvent(UUID.randomUUID(), "PAYMENT_FAILED", payload);
        outboxEventRepository.saveAndFlush(event);

        OutboxEvent found = outboxEventRepository.findById(event.getId()).orElseThrow();
        assertThat(found.getPayload()).contains("retryCount").contains("final-attempt");
    }
}
