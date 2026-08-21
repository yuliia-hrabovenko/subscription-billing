package com.subscriptionbilling.billingcore.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Append-only record of a Subscription state transition: who/what/when/old/new
 */
@Entity
@Table(name = "audit_log_entry")
public class AuditLogEntry {

    @Id
    private UUID id;

    @Column(name = "subscription_id", nullable = false)
    private UUID subscriptionId;

    @Column(nullable = false)
    private String actor;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "old_value")
    private String oldValue;

    @Column(name = "new_value")
    private String newValue;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected AuditLogEntry() {
    }

    public AuditLogEntry(UUID id, UUID subscriptionId, String actor, String eventType,
                          String oldValue, String newValue) {
        this.id = id;
        this.subscriptionId = subscriptionId;
        this.actor = actor;
        this.eventType = eventType;
        this.oldValue = oldValue;
        this.newValue = newValue;
        this.occurredAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getSubscriptionId() {
        return subscriptionId;
    }

    public String getActor() {
        return actor;
    }

    public String getEventType() {
        return eventType;
    }

    public String getOldValue() {
        return oldValue;
    }

    public String getNewValue() {
        return newValue;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
