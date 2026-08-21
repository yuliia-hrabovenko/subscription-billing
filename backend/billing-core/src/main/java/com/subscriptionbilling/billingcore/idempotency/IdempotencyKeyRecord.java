package com.subscriptionbilling.billingcore.idempotency;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.UUID;

/**
 * A generic dedupe record for a client-supplied {@code Idempotency-Key} header, scoped
 * per customer and per operation (e.g. "plan-change", "cancel") so the same key value
 * can't collide across unrelated endpoints. Retained only until {@code expiresAt}.
 */
@Entity
@Table(name = "idempotency_key",
        uniqueConstraints = @UniqueConstraint(columnNames = {"customer_id", "operation", "idempotency_key"}))
public class IdempotencyKeyRecord {

    @Id
    private UUID id;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(nullable = false)
    private String operation;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected IdempotencyKeyRecord() {
    }

    public IdempotencyKeyRecord(UUID id, UUID customerId, String operation, String idempotencyKey,
                                 Instant createdAt, Instant expiresAt) {
        this.id = id;
        this.customerId = customerId;
        this.operation = operation;
        this.idempotencyKey = idempotencyKey;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public String getOperation() {
        return operation;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
