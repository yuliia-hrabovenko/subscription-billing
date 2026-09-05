package com.subscriptionbilling.billingcore.subscription;

import java.util.UUID;

/**
 * A cancel request targeted a Subscription that's already {@code canceled}. Terminal
 * — no transition leaves {@code canceled}, so canceling it again is
 * rejected rather than silently treated as a no-op.
 */
public class SubscriptionAlreadyCanceledException extends RuntimeException {

    public SubscriptionAlreadyCanceledException(UUID subscriptionId) {
        super("Subscription " + subscriptionId + " is already canceled and cannot be modified.");
    }
}
