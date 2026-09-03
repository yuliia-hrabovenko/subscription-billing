package com.subscriptionbilling.billingcore.customer;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * The individual who owns at most one non-{@code canceled} Subscription at a time. Their
 * card on file is not carried here -- see ADR-0011: it lives in the {@code payments}
 * module's {@code payment_method} table, resolved via {@link
 * com.subscriptionbilling.billingjob.paymentmethod.CustomerPaymentMethodPort} rather than
 * a field on this entity.
 */
@Entity
@Table(name = "customer")
public class Customer {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash")
    private String passwordHash;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Customer() {
    }

    public Customer(UUID id, String email) {
        this.id = id;
        this.email = email;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
