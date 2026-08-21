package com.subscriptionbilling.notifications.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * A fact ready to publish downstream, written in the same database transaction as the
 * state change it reports (the Transactional Outbox pattern). {@code publishedAt} stays
 * null until the relay publishes it. {@code eventType}/{@code payload} are deliberately
 * generic so a new event type is a new value, not a schema change.
 */
@Entity
@Table(name = "outbox_event")
public class OutboxEvent {

    @Id
    private UUID id;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    protected OutboxEvent() {
    }

    public OutboxEvent(UUID id, String eventType, String payload) {
        this.id = id;
        this.eventType = eventType;
        this.payload = payload;
        this.createdAt = Instant.now();
    }

    public void markPublished(Instant publishedAt) {
        if (publishedAt == null) {
            throw new IllegalArgumentException("publishedAt must not be null");
        }
        if (this.publishedAt != null) {
            throw new IllegalStateException("OutboxEvent " + id + " is already published at " + this.publishedAt);
        }
        this.publishedAt = publishedAt;
    }

    public UUID getId() {
        return id;
    }

    public String getEventType() {
        return eventType;
    }

    public String getPayload() {
        return payload;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }
}
