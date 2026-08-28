package com.subscriptionbilling.webhooks.dedupe;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.UUID;

/**
 * A record of one gateway event already processed, keyed by its {@code
 * gatewayEventId}. The database-level unique constraint on that column is the actual
 * dedupe mechanism — this entity only carries the values inserted into it.
 */
@Entity
@Table(name = "webhook_event",
        uniqueConstraints = @UniqueConstraint(columnNames = "gateway_event_id"))
public class WebhookEvent {

    @Id
    private UUID id;

    @Column(name = "gateway_event_id", nullable = false)
    private String gatewayEventId;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    protected WebhookEvent() {
    }

    public WebhookEvent(UUID id, String gatewayEventId, Instant receivedAt) {
        this.id = id;
        this.gatewayEventId = gatewayEventId;
        this.receivedAt = receivedAt;
    }

    public UUID getId() {
        return id;
    }

    public String getGatewayEventId() {
        return gatewayEventId;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }
}
