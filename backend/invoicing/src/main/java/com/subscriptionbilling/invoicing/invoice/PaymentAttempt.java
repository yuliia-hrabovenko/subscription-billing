package com.subscriptionbilling.invoicing.invoice;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A single try at charging the Customer's card against an {@link Invoice} — up to four
 * per Invoice (initial charge plus three Dunning retries). {@code invoice} is a JPA
 * relation rather than a raw UUID: unlike Subscription/PriceVersion, Invoice is owned by
 * this same module, so the relation doesn't cross a module boundary.
 */
@Entity
@Table(name = "payment_attempt")
public class PaymentAttempt {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invoice_id", nullable = false)
    private Invoice invoice;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentAttemptStatus status;

    @Column(name = "attempted_at", nullable = false)
    private Instant attemptedAt;

    @Column(name = "gateway_reference")
    private String gatewayReference;

    protected PaymentAttempt() {
    }

    /**
     * @param id          the PaymentAttempt's identity
     * @param invoice     the Invoice this charge try was made against
     * @param status      the gateway's resolved outcome for this try
     * @param attemptedAt the instant the gateway resolved this try
     */
    public PaymentAttempt(UUID id, Invoice invoice, PaymentAttemptStatus status, Instant attemptedAt) {
        this(id, invoice, status, attemptedAt, null);
    }

    /**
     * @param id               the PaymentAttempt's identity
     * @param invoice          the Invoice this charge try was made against
     * @param status           the gateway's resolved outcome for this try
     * @param attemptedAt      the instant the gateway resolved this try
     * @param gatewayReference the gateway's reference for this try, or null if the
     *                         gateway didn't supply one — the correlation key a
     *                         payment-succeeded/failed webhook event uses to find this
     *                         attempt
     */
    public PaymentAttempt(UUID id, Invoice invoice, PaymentAttemptStatus status, Instant attemptedAt,
                           String gatewayReference) {
        this.id = id;
        this.invoice = invoice;
        this.status = status;
        this.attemptedAt = attemptedAt;
        this.gatewayReference = gatewayReference;
    }

    public UUID getId() {
        return id;
    }

    public Invoice getInvoice() {
        return invoice;
    }

    public PaymentAttemptStatus getStatus() {
        return status;
    }

    public Instant getAttemptedAt() {
        return attemptedAt;
    }

    public String getGatewayReference() {
        return gatewayReference;
    }
}
