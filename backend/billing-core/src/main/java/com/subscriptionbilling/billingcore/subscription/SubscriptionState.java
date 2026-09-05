package com.subscriptionbilling.billingcore.subscription;

/**
 * Lifecycle states from state diagram.
 * {@code CANCELED} is terminal.
 */
public enum SubscriptionState {
    TRIALING,
    ACTIVE,
    PENDING_CANCELLATION,
    SUSPENDED,
    CANCELED
}
