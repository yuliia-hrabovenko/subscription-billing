package com.subscriptionbilling.webhooks.dedupe;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Dedupes a gateway event by its {@code gatewayEventId}, permanently — unlike a
 * customer-supplied idempotency key, a webhook event id is never eligible for reuse,
 * so once recorded it stays recorded.
 */
@Service
public class WebhookDedupeService {

    private final WebhookEventRepository repository;
    private final Clock clock;

    @Autowired
    public WebhookDedupeService(WebhookEventRepository repository) {
        this(repository, Clock.systemUTC());
    }

    WebhookDedupeService(WebhookEventRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * Records {@code gatewayEventId} as processed if it hasn't been seen before. A
     * concurrent race for the same id resolves to exactly one caller getting {@code
     * true}, backed by the underlying table's unique constraint.
     *
     * @param gatewayEventId the gateway's event identifier
     * @return true the first time this id is recorded; false if it was already present
     */
    public boolean recordIfNew(String gatewayEventId) {
        try {
            repository.insert(new WebhookEvent(UUID.randomUUID(), gatewayEventId, Instant.now(clock)));
            return true;
        } catch (DataIntegrityViolationException alreadyProcessed) {
            return false;
        }
    }
}
