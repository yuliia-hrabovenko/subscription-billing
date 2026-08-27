package com.subscriptionbilling.billingcore.subscription;

import java.util.UUID;

/**
 * A self-service payment retry was attempted on a Subscription that isn't currently
 * {@code suspended} — the only state a Customer can trigger an extra retry from.
 */
public class SubscriptionNotSuspendedException extends RuntimeException {

    public SubscriptionNotSuspendedException(UUID subscriptionId, SubscriptionState currentState) {
        super("Subscription " + subscriptionId + " is not suspended (current state: " + currentState + ").");
    }
}
