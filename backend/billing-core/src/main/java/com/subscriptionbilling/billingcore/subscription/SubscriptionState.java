package com.subscriptionbilling.billingcore.subscription;

/**
 * Lifecycle states from {@code docs/domain/domain-model.md}'s state diagram.
 * {@code CANCELED} is terminal.
 */
public enum SubscriptionState {
    TRIALING,
    ACTIVE,
    PENDING_CANCELLATION,
    SUSPENDED,
    CANCELED
}
