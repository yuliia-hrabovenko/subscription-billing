package com.subscriptionbilling.billingcore.subscription;

import java.util.UUID;

/**
 * Covers every non-{@code pending_cancellation} originating state with one exception:
 * from the caller's perspective, "never canceled" and "cancellation already took
 * effect" are the same outcome — nothing pending to undo.
 */
public class SubscriptionNotPendingCancellationException extends RuntimeException {

    public SubscriptionNotPendingCancellationException(UUID subscriptionId, SubscriptionState currentState) {
        super("Subscription " + subscriptionId + " is not pending cancellation (current state: " + currentState + ").");
    }
}
