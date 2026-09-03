package com.subscriptionbilling.billingjob.paymentmethod;

/**
 * The gateway-reported shape of a just-attached payment method, resolved by {@link
 * PaymentMethodOnboardingPort#attach} straight from the gateway's own response -- never
 * derived from a raw card number (PAN), which this system never receives.
 *
 * @param providerPaymentMethodId the gateway's reference for the card itself (e.g.
 *                                Stripe's {@code pm_...}) -- what billing charges against
 * @param type                    the gateway's payment method type (e.g. {@code "card"})
 * @param brand                   the card network brand (e.g. {@code "visa"}), or null if
 *                                the gateway didn't report one
 * @param last4                   the card's last four digits, or null if the gateway
 *                                didn't report them
 * @param expiryMonth             the card's expiry month, or null if not a card
 * @param expiryYear              the card's expiry year, or null if not a card
 */
public record PaymentMethodDetails(String providerPaymentMethodId, String type, String brand, String last4,
                                    Integer expiryMonth, Integer expiryYear) {
}
