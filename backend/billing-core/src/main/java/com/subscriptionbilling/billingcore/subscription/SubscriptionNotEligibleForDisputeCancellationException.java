package com.subscriptionbilling.billingcore.subscription;

import java.util.UUID;

/**
 * The originating state was neither {@code active} nor {@code suspended} — the only two
 * states a dispute-triggered cancellation can transition out of.
 */
public class SubscriptionNotEligibleForDisputeCancellationException extends RuntimeException {

    public SubscriptionNotEligibleForDisputeCancellationException(UUID subscriptionId, SubscriptionState currentState) {
        super("Subscription " + subscriptionId + " is not eligible for dispute cancellation (current state: "
                + currentState + ").");
    }
}
