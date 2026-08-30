package com.subscriptionbilling.notifications.relay;

import com.subscriptionbilling.notifications.outbox.OutboxEvent;
import com.subscriptionbilling.notifications.outbox.OutboxEventRepository;
import com.subscriptionbilling.notifications.support.AbstractPostgresIntegrationTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class OutboxRelayMetricsTest extends AbstractPostgresIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-08-30T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void exposesZeroUnpublishedRowsAndZeroOldestAgeWhenTheOutboxIsEmpty() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        new OutboxRelayMetrics(meterRegistry, outboxEventRepository, CLOCK);

        assertThat(meterRegistry.get("outbox_relay_unpublished_rows").gauge().value()).isZero();
        assertThat(meterRegistry.get("outbox_relay_oldest_unpublished_age_seconds").gauge().value()).isZero();
    }

    @Test
    void countsOnlyUnpublishedRowsAndReportsTheOldestOnesAge() {
        Instant oldCreatedAt = NOW.minus(Duration.ofHours(2));
        UUID oldEventId = UUID.randomUUID();
        outboxEventRepository.saveAndFlush(new OutboxEvent(oldEventId, "PAYMENT_FAILED", "{}"));
        outboxEventRepository.saveAndFlush(new OutboxEvent(UUID.randomUUID(), "TRIAL_ENDING_SOON", "{}"));
        OutboxEvent published = new OutboxEvent(UUID.randomUUID(), "CANCELLATION_CONFIRMED", "{}");
        published.markPublished(NOW);
        outboxEventRepository.saveAndFlush(published);
        backdateCreatedAt(oldEventId, oldCreatedAt);

        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        new OutboxRelayMetrics(meterRegistry, outboxEventRepository, CLOCK);

        assertThat(meterRegistry.get("outbox_relay_unpublished_rows").gauge().value()).isEqualTo(2.0);
        assertThat(meterRegistry.get("outbox_relay_oldest_unpublished_age_seconds").gauge().value())
                .isEqualTo(Duration.ofHours(2).getSeconds());
    }

    /**
     * {@link OutboxEvent} stamps {@code created_at} from the wall clock in its
     * constructor with no setter (by design -- production code never backdates a row);
     * this bypasses that entirely via a native update, then evicts the persistence
     * context so a later read goes back to the database instead of the stale cached value.
     */
    private void backdateCreatedAt(UUID eventId, Instant createdAt) {
        entityManager.getEntityManager()
                .createNativeQuery("update outbox_event set created_at = ?1 where id = ?2")
                .setParameter(1, createdAt)
                .setParameter(2, eventId)
                .executeUpdate();
        entityManager.clear();
    }
}
