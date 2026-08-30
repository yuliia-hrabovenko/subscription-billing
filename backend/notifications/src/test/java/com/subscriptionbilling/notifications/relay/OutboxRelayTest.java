package com.subscriptionbilling.notifications.relay;

import com.subscriptionbilling.notifications.outbox.OutboxEvent;
import com.subscriptionbilling.notifications.outbox.OutboxEventRepository;
import com.subscriptionbilling.notifications.support.AbstractPostgresIntegrationTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real Postgres behind {@link OutboxEventRepository}, a hand-built {@link EventPublisher}
 * test double standing in for Kafka -- this is the seam for {@link OutboxRelay}'s own
 * poll/publish/retry orchestration, not the Kafka wire contract itself (see {@code
 * OutboxRelayKafkaIT} for that boundary).
 */
@DataJpaTest
class OutboxRelayTest extends AbstractPostgresIntegrationTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-30T00:00:00Z"), ZoneOffset.UTC);

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Test
    void aFailedPublishLeavesTheRowUnpublishedAndTheNextPollRetriesIt() {
        AtomicBoolean shouldFail = new AtomicBoolean(true);
        EventPublisher flakyPublisher = event -> {
            if (shouldFail.get()) {
                throw new EventPublishException("Simulated transient publish failure", new RuntimeException("boom"));
            }
        };
        OutboxRelay relay = newRelay(flakyPublisher);
        OutboxEvent event = outboxEventRepository.saveAndFlush(
                new OutboxEvent(UUID.randomUUID(), "PAYMENT_SUCCEEDED", "{}"));

        assertThat(relay.pollAndPublish()).isZero();
        assertThat(outboxEventRepository.findById(event.getId()).orElseThrow().getPublishedAt()).isNull();

        shouldFail.set(false);

        assertThat(relay.pollAndPublish()).isOne();
        assertThat(outboxEventRepository.findById(event.getId()).orElseThrow().getPublishedAt()).isNotNull();
    }

    @Test
    void aRowSeededDirectlyWithNoTriggeringTransactionInThisProcessIsStillPublished() {
        // Simulates a crash between the triggering transaction's commit and the relay
        // publishing it: the row exists with no in-memory trace of how it got there.
        OutboxEvent event = outboxEventRepository.saveAndFlush(
                new OutboxEvent(UUID.randomUUID(), "CANCELLATION_CONFIRMED", "{\"subscriptionId\":\"s1\",\"customerId\":\"c1\"}"));
        OutboxRelay relay = newRelay(publishedEvent -> { });

        assertThat(relay.pollAndPublish()).isOne();
        assertThat(outboxEventRepository.findById(event.getId()).orElseThrow().getPublishedAt()).isNotNull();
    }

    @Test
    void skipsRowsAlreadyPublished() {
        OutboxEvent alreadyPublished = new OutboxEvent(UUID.randomUUID(), "TRIAL_ENDING_SOON", "{}");
        alreadyPublished.markPublished(Instant.now(CLOCK));
        outboxEventRepository.saveAndFlush(alreadyPublished);
        OutboxRelay relay = newRelay(event -> {
            throw new AssertionError("an already-published row must never be republished");
        });

        assertThat(relay.pollAndPublish()).isZero();
    }

    private OutboxRelay newRelay(EventPublisher eventPublisher) {
        return new OutboxRelay(outboxEventRepository, eventPublisher,
                new OutboxRelayMetrics(new SimpleMeterRegistry(), outboxEventRepository, CLOCK), CLOCK);
    }
}
