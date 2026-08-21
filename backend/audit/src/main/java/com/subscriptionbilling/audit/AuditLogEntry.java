package com.subscriptionbilling.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Append-only record of a Subscription state transition: who/what/when/old/new.
 * Immutable once written, exposes no update or delete operation, so this row can't be
 * altered after the fact.
 *
 * <p>{@code oldState}/{@code newState} are plain strings, not the calling module's state
 * enum: this module is a dependency of nearly every other module (billing-core,
 * billing-job, dunning, webhooks), so it must not depend back on any of them just to
 * describe log data — the same reasoning that keeps {@code OutboxEvent.eventType} generic.
 */
@Entity
@Table(name = "audit_log_entry")
public class AuditLogEntry {

    @Id
    private UUID id;

    @Column(name = "subscription_id", nullable = false)
    private UUID subscriptionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false)
    private ActorType actorType;

    @Column(name = "old_state")
    private String oldState;

    @Column(name = "new_state", nullable = false)
    private String newState;

    @Column(name = "correlation_id", nullable = false)
    private String correlationId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected AuditLogEntry() {
    }

    public AuditLogEntry(UUID id, UUID subscriptionId, ActorType actorType, String oldState,
                          String newState, String correlationId) {
        this.id = id;
        this.subscriptionId = subscriptionId;
        this.actorType = actorType;
        this.oldState = oldState;
        this.newState = newState;
        this.correlationId = correlationId;
        this.occurredAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getSubscriptionId() {
        return subscriptionId;
    }

    public ActorType getActorType() {
        return actorType;
    }

    public String getOldState() {
        return oldState;
    }

    public String getNewState() {
        return newState;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
