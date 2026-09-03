package com.subscriptionbilling.billingjob.paymentmethod;

/**
 * A Customer's card on file, as {@link CustomerPaymentMethodPort#findActiveProviderReference}
 * resolves it: both gateway ids a charge needs, always fetched and passed together -- a
 * charge can never legitimately combine a payment method reference from one Customer's card
 * with a gateway Customer id looked up separately.
 *
 * @param providerCustomerId      the gateway's identity for the Customer this card is
 *                                attached to (e.g. Stripe's {@code cus_...})
 * @param providerPaymentMethodId the gateway's reference for the card itself (e.g. Stripe's
 *                                {@code pm_...}) -- what billing charges against
 */
public record ProviderPaymentMethodReference(String providerCustomerId, String providerPaymentMethodId) {
}
