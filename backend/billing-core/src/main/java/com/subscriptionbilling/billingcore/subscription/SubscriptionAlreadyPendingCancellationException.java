package com.subscriptionbilling.billingcore.subscription;

import java.util.UUID;

/**
 * A cancel request targeted a Subscription that's already {@code
 * pending_cancellation}. A Subscription holds at most one outstanding cancellation, so
 * a second cancel request while one is already pending is rejected rather than
 * silently treated as a no-op — the caller most likely means {@code undo-cancel}
 * followed by a fresh {@code cancel} if they intend to reset the pending period-end
 * date, not a repeat of this same request.
 */
public class SubscriptionAlreadyPendingCancellationException extends RuntimeException {

    public SubscriptionAlreadyPendingCancellationException(UUID subscriptionId) {
        super("Subscription " + subscriptionId + " is already pending cancellation.");
    }
}
