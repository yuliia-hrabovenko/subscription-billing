package com.subscriptionbilling.billingcore.subscription;

import java.util.UUID;

/**
 * An undo-cancel request targeted a Subscription that isn't currently {@code
 * pending_cancellation} — nothing pending to undo, whether because it was never
 * canceled ({@code trialing}, {@code active}, {@code suspended}) or because its
 * cancellation already took effect ({@code canceled}, terminal per Invariant 7). One
 * exception covers every such originating state: from the caller's perspective they're
 * all the same outcome, "there's no pending cancellation on this Subscription to undo."
 */
public class SubscriptionNotPendingCancellationException extends RuntimeException {

    public SubscriptionNotPendingCancellationException(UUID subscriptionId, SubscriptionState currentState) {
        super("Subscription " + subscriptionId + " is not pending cancellation (current state: " + currentState + ").");
    }
}
