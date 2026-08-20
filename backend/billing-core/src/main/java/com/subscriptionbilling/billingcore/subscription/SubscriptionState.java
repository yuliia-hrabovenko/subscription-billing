package com.subscriptionbilling.billingcore.subscription;

/**
 * Lifecycle states from {@code docs/domain/domain-model.md}'s state diagram.
 * {@code CANCELED} is terminal (Invariant 7) — no transition leaves it.
 */
public enum SubscriptionState {
    TRIALING,
    ACTIVE,
    PENDING_CANCELLATION,
    SUSPENDED,
    CANCELED
}
