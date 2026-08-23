package com.subscriptionbilling.billingcore.subscription;

import java.util.UUID;

/**
 * Covers every non-{@code ACTIVE} originating state with one exception: a plan change
 * only applies to a Subscription currently holding active paid or active free access to
 * change out of — {@code trialing}, {@code pending_cancellation}, {@code suspended}, and
 * {@code canceled} are all rejected the same way.
 */
public class SubscriptionNotEligibleForPlanChangeException extends RuntimeException {

    public SubscriptionNotEligibleForPlanChangeException(UUID subscriptionId, SubscriptionState currentState) {
        super("Subscription " + subscriptionId + " is not eligible for a plan change (current state: " + currentState + ").");
    }
}
