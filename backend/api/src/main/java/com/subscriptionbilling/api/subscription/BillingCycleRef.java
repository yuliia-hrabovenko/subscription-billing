package com.subscriptionbilling.api.subscription;

import java.time.Instant;

/**
 * Wire representation of a Subscription's Billing Cycle, shared by every response that
 * can carry one ({@link SignupResponse}, {@link SubscriptionResponse}) so the shape
 * can't drift between them. {@code anchoredAt} is the instant the Billing Cycle
 * started (and, once a billing schedule exists elsewhere, recurs from).
 */
public record BillingCycleRef(Instant anchoredAt) {

    static BillingCycleRef from(Instant billingCycleAnchor) {
        return billingCycleAnchor != null ? new BillingCycleRef(billingCycleAnchor) : null;
    }
}
