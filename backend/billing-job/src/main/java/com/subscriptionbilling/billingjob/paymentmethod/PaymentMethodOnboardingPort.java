package com.subscriptionbilling.billingjob.paymentmethod;

import java.util.UUID;

/**
 * The seam signup attaches a Customer's gateway-tokenized card through, so the
 * Subscription-lifecycle module never depends on a specific gateway's onboarding API
 * shape -- the counterpart, for saving a card, to {@link
 * com.subscriptionbilling.billingjob.gateway.PaymentGatewayClient} for charging one.
 *
 * <p>PAN safety: {@link #attach} takes only a gateway-provided payment-method reference
 * (e.g. Stripe's {@code pm_...}, produced client-side by the gateway's own JS SDK), never
 * a raw card number.
 */
public interface PaymentMethodOnboardingPort {

    /**
     * Attaches an already-tokenized payment method to the given Customer as their new
     * card on file, replacing whatever card was previously on file for them.
     *
     * @param customerId              the Customer the card belongs to
     * @param providerPaymentMethodId the gateway-provided payment-method reference to attach
     * @return the gateway-reported shape of the now-attached card
     */
    PaymentMethodDetails attach(UUID customerId, String providerPaymentMethodId);
}
