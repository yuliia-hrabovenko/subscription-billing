package com.subscriptionbilling.notifications.relay;

import com.subscriptionbilling.notifications.outbox.OutboxEventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * The relay's Prometheus surface: how many rows the relay has published/failed to
 * publish since startup, plus two live gauges -- current unpublished-row count and the
 * oldest unpublished row's age -- so a stalled or crashed relay is visible from a metric
 * (unpublished count climbing, oldest age growing unbounded) rather than only from a
 * customer-reported missing notification.
 */
@Component
public class OutboxRelayMetrics {

    private final Counter published;
    private final Counter publishFailures;

    @Autowired
    public OutboxRelayMetrics(MeterRegistry meterRegistry, OutboxEventRepository outboxEventRepository) {
        this(meterRegistry, outboxEventRepository, Clock.systemUTC());
    }

    OutboxRelayMetrics(MeterRegistry meterRegistry, OutboxEventRepository outboxEventRepository, Clock clock) {
        this.published = Counter.builder("outbox_relay_events_published_total")
                .description("Outbox rows successfully published to Kafka")
                .register(meterRegistry);
        this.publishFailures = Counter.builder("outbox_relay_publish_failures_total")
                .description("Outbox publish attempts that failed and were left for the next poll to retry")
                .register(meterRegistry);
        Gauge.builder("outbox_relay_unpublished_rows", outboxEventRepository, OutboxEventRepository::countByPublishedAtIsNull)
                .description("Outbox rows not yet published to Kafka")
                .register(meterRegistry);
        Gauge.builder("outbox_relay_oldest_unpublished_age_seconds", outboxEventRepository,
                        repository -> oldestUnpublishedAgeSeconds(repository, clock))
                .description("Age in seconds of the oldest unpublished outbox row, 0 when none are unpublished")
                .register(meterRegistry);
    }

    void recordPublished() {
        published.increment();
    }

    void recordPublishFailure() {
        publishFailures.increment();
    }

    private static double oldestUnpublishedAgeSeconds(OutboxEventRepository repository, Clock clock) {
        return repository.findFirstByPublishedAtIsNullOrderByCreatedAtAsc()
                .map(oldest -> Duration.between(oldest.getCreatedAt(), Instant.now(clock)).getSeconds())
                .orElse(0L)
                .doubleValue();
    }
}
