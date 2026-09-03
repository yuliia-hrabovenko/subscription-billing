package com.subscriptionbilling.api.paymentmethod;

/** {@code POST /api/v1/payment-methods/setup-intent}'s response. */
public record SetupIntentResponse(String clientSecret) {
}
