package com.subscriptionbilling.payments.paymentmethod;

import com.subscriptionbilling.billingjob.paymentmethod.PaymentMethodDetails;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A Customer's tokenized card on file: a gateway-issued reference, never a raw card
 * number (PAN). {@code customerId} is a plain UUID, not a JPA relation
 * a different module, and this module must not take on a
 * cross-module entity dependency (see {@code invoice.subscription_id}'s precedent).
 *
 * <p>Exactly one row per {@code customerId} is an application-level invariant enforced by
 * {@link PaymentMethodOnboardingService} (which replaces an existing row rather than
 * inserting a second one), not a schema constraint -- this domain has exactly one card on
 * file per Customer today.
 */
@Entity
@Table(name = "payment_method")
public class PaymentMethod {

    @Id
    private UUID id;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(nullable = false)
    private String provider;

    /** The gateway's identity for the Customer this card is attached to (e.g. Stripe's {@code cus_...}). */
    @Column(name = "provider_customer_id", nullable = false)
    private String providerCustomerId;

    /** The gateway's reference for this card itself (e.g. Stripe's {@code pm_...}) -- what billing charges against. */
    @Column(name = "provider_payment_method_id", nullable = false)
    private String providerPaymentMethodId;

    @Column(nullable = false)
    private String type;

    private String brand;

    private String last4;

    @Column(name = "expiry_month")
    private Integer expiryMonth;

    @Column(name = "expiry_year")
    private Integer expiryYear;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected PaymentMethod() {
    }

    public PaymentMethod(UUID id, UUID customerId, String provider, String providerCustomerId,
                          PaymentMethodDetails details) {
        this.id = id;
        this.customerId = customerId;
        this.provider = provider;
        this.providerCustomerId = providerCustomerId;
        this.providerPaymentMethodId = details.providerPaymentMethodId();
        this.type = details.type();
        this.brand = details.brand();
        this.last4 = details.last4();
        this.expiryMonth = details.expiryMonth();
        this.expiryYear = details.expiryYear();
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public String getProvider() {
        return provider;
    }

    public String getProviderCustomerId() {
        return providerCustomerId;
    }

    public String getProviderPaymentMethodId() {
        return providerPaymentMethodId;
    }

    public String getType() {
        return type;
    }

    public String getBrand() {
        return brand;
    }

    public String getLast4() {
        return last4;
    }

    public Integer getExpiryMonth() {
        return expiryMonth;
    }

    public Integer getExpiryYear() {
        return expiryYear;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
