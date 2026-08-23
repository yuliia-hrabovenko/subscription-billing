package com.subscriptionbilling.billingcore.subscription;

import java.util.UUID;

/**
 * The originating state was neither {@code trialing} nor {@code active} — the only two
 * edges into {@code suspended}.
 */
public class SubscriptionNotEligibleForSuspensionException extends RuntimeException {

    public SubscriptionNotEligibleForSuspensionException(UUID subscriptionId, SubscriptionState currentState) {
        super("Subscription " + subscriptionId + " is not eligible for suspension (current state: " + currentState + ").");
    }
}
