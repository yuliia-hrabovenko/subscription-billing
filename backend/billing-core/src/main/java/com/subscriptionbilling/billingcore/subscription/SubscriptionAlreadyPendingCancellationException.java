package com.subscriptionbilling.billingcore.subscription;

import java.util.UUID;

/**
 * A Subscription holds at most one outstanding cancellation. Rejected rather than
 * treated as a no-op — the caller most likely means {@code undo-cancel} followed by a
 * fresh {@code cancel} if they intend to reset the pending period-end date.
 */
public class SubscriptionAlreadyPendingCancellationException extends RuntimeException {

    public SubscriptionAlreadyPendingCancellationException(UUID subscriptionId) {
        super("Subscription " + subscriptionId + " is already pending cancellation.");
    }
}
