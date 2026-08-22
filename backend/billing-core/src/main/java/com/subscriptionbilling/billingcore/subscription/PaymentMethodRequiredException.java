package com.subscriptionbilling.billingcore.subscription;

import java.util.UUID;

/**
 * Both paid signup paths need a card on file: a Trial because it auto-converts to a
 * real charge when it ends, and immediate-paid because it's about to be charged.
 */
public class PaymentMethodRequiredException extends RuntimeException {

    public PaymentMethodRequiredException(UUID planId) {
        super("A payment method token is required to sign up for plan " + planId);
    }
}
