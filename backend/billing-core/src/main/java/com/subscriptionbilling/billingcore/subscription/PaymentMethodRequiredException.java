package com.subscriptionbilling.billingcore.subscription;

import java.util.UUID;

/**
 * A signup request for a paid Plan (Trial or immediate-paid) was made without a
 * {@code paymentMethodToken}. Both paid signup paths need a card on file: a Trial
 * because it auto-converts to a real charge when it ends, and immediate-paid because
 * it's about to be charged. A free-Plan signup never needs a payment method and never
 * triggers this check.
 */
public class PaymentMethodRequiredException extends RuntimeException {

    public PaymentMethodRequiredException(UUID planId) {
        super("A payment method token is required to sign up for plan " + planId);
    }
}
