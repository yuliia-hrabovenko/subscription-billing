package com.subscriptionbilling.invoicing.invoice;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The record of a Billing Cycle's charge — exactly one per {@code (subscriptionId,
 * billingPeriod)}, enforced by a database unique constraint so a crashed-and-restarted
 * or duplicate billing-job run can never create a second one for the same cycle.
 *
 * <p>{@code subscriptionId} and {@code priceVersionId} are plain UUIDs, not JPA
 * relations: Subscription and PriceVersion belong to billing-core, a different module,
 * and this module must not take on a cross-module entity dependency. {@code
 * priceVersionId} is fixed at construction and never reassigned; the PriceVersion row it
 * points at is itself immutable, so this reference alone keeps a charged Invoice immune
 * to a later Plan price change.
 */
@Entity
@Table(name = "invoice",
        uniqueConstraints = @UniqueConstraint(name = "uq_invoice_subscription_billing_period",
                columnNames = {"subscription_id", "billing_period"}))
public class Invoice {

    /** Dunning retries are bounded at 3 (Invariant 11): initial charge plus at most 3 retries. */
    public static final int MAX_RETRIES = 3;

    @Id
    private UUID id;

    @Column(name = "subscription_id", nullable = false)
    private UUID subscriptionId;

    @Column(name = "billing_period", nullable = false)
    private LocalDate billingPeriod;

    @Column(name = "price_version_id", nullable = false)
    private UUID priceVersionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private InvoiceStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "retries_used", nullable = false)
    private int retriesUsed;

    protected Invoice() {
    }

    /**
     * @param id             the Invoice's identity
     * @param subscriptionId the Subscription being charged
     * @param billingPeriod  the Billing Cycle date this charge is for; paired with
     *                       {@code subscriptionId} as the uniqueness key
     * @param priceVersionId the PriceVersion charged
     */
    public Invoice(UUID id, UUID subscriptionId, LocalDate billingPeriod, UUID priceVersionId) {
        this.id = id;
        this.subscriptionId = subscriptionId;
        this.billingPeriod = billingPeriod;
        this.priceVersionId = priceVersionId;
        this.status = InvoiceStatus.OPEN;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getSubscriptionId() {
        return subscriptionId;
    }

    public LocalDate getBillingPeriod() {
        return billingPeriod;
    }

    public UUID getPriceVersionId() {
        return priceVersionId;
    }

    public InvoiceStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public int getRetriesUsed() {
        return retriesUsed;
    }

    /**
     * @return whether {@link #MAX_RETRIES} retry Payment Attempts (scheduled or
     * self-service) have already been recorded against this Invoice, independent of
     * which day each one happened on
     */
    public boolean retriesExhausted() {
        return retriesUsed >= MAX_RETRIES;
    }

    /**
     * Records that one retry Payment Attempt (scheduled or self-service) was made
     * against this Invoice.
     *
     * @throws IllegalStateException if {@link #retriesExhausted()} is already true
     */
    public void recordRetryAttempt() {
        if (retriesExhausted()) {
            throw new IllegalStateException("retriesUsed already at the cap of " + MAX_RETRIES);
        }
        retriesUsed++;
    }
}
