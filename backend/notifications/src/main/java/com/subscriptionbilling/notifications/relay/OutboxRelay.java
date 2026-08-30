package com.subscriptionbilling.notifications.relay;

import com.subscriptionbilling.notifications.outbox.OutboxEvent;
import com.subscriptionbilling.notifications.outbox.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Polls for unpublished {@link OutboxEvent} rows and publishes each via {@link
 * EventPublisher}, marking {@code published_at} only after the publish succeeds. Reads
 * only rows already committed by their triggering transaction (per the outbox pattern,
 * this relay never participates in that transaction), so a crash or restart between a
 * trigger's commit and this relay publishing it simply leaves the row for the next poll
 * to pick up -- the same code path as an ordinary poll, not a special recovery mode.
 *
 * <p>Each row's publish and its {@code published_at} update are two separate steps, the
 * publish outside any database transaction and the update in its own short one -- a
 * Kafka call never runs inside an open transaction. One row's publish failure is caught,
 * counted, logged, and left unpublished for the next poll; it does not stop the rest of
 * the batch. A duplicate publish of the same row across two concurrent relay instances is
 * possible and accepted -- Kafka delivery here is at-least-once by design, and consumers
 * are expected to be idempotent against the message key (the outbox row id).
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final int BATCH_SIZE = 100;

    private final OutboxEventRepository outboxEventRepository;
    private final EventPublisher eventPublisher;
    private final OutboxRelayMetrics metrics;
    private final Clock clock;

    @Autowired
    public OutboxRelay(OutboxEventRepository outboxEventRepository, EventPublisher eventPublisher,
                        OutboxRelayMetrics metrics) {
        this(outboxEventRepository, eventPublisher, metrics, Clock.systemUTC());
    }

    OutboxRelay(OutboxEventRepository outboxEventRepository, EventPublisher eventPublisher,
                OutboxRelayMetrics metrics, Clock clock) {
        this.outboxEventRepository = outboxEventRepository;
        this.eventPublisher = eventPublisher;
        this.metrics = metrics;
        this.clock = clock;
    }

    /**
     * The scheduled entry point: delegates to {@link #pollAndPublish()} and logs a
     * summary. Runs every {@code notifications.relay.poll-interval-ms} (default 5000),
     * with the same interval as its own initial delay so this never races a test or a
     * fresh app instance's own startup-time publish.
     */
    @Scheduled(initialDelayString = "${notifications.relay.poll-interval-ms:5000}",
            fixedDelayString = "${notifications.relay.poll-interval-ms:5000}")
    public void poll() {
        int publishedCount = pollAndPublish();
        if (publishedCount > 0) {
            log.info("Outbox relay published {} event(s)", publishedCount);
        }
    }

    /**
     * Publishes up to {@link #BATCH_SIZE} unpublished rows, oldest first, and returns how
     * many succeeded. Exposed separately from {@link #poll()} so a caller (a test, or a
     * manual trigger) can run one pass synchronously without going through Spring's
     * scheduler.
     */
    public int pollAndPublish() {
        List<OutboxEvent> batch = outboxEventRepository.findByPublishedAtIsNullOrderByCreatedAtAsc(
                PageRequest.of(0, BATCH_SIZE));
        int publishedCount = 0;
        for (OutboxEvent event : batch) {
            if (publishOne(event)) {
                publishedCount++;
            }
        }
        return publishedCount;
    }

    private boolean publishOne(OutboxEvent event) {
        try {
            eventPublisher.publish(event);
        } catch (RuntimeException publishFailed) {
            log.warn("Failed to publish outbox event {} (type {}); will retry next poll",
                    event.getId(), event.getEventType(), publishFailed);
            metrics.recordPublishFailure();
            return false;
        }
        markPublished(event.getId());
        metrics.recordPublished();
        return true;
    }

    @Transactional
    void markPublished(UUID eventId) {
        OutboxEvent event = outboxEventRepository.findById(eventId)
                .orElseThrow(() -> new IllegalStateException("OutboxEvent " + eventId + " no longer exists"));
        event.markPublished(Instant.now(clock));
        outboxEventRepository.save(event);
    }
}
