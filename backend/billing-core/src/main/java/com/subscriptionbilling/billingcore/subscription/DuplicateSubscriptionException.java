package com.subscriptionbilling.billingcore.subscription;

import java.util.UUID;

/**
 * Enforces Invariant 1: a Customer may hold at most one non-{@code canceled}
 * Subscription at a time — multiple over a lifetime are fine (re-subscribing after a
 * cancellation), concurrent ones are not. Only reachable when the signup request
 * identified an existing Customer; a brand-new Customer can never already have one.
 */
public class DuplicateSubscriptionException extends RuntimeException {

    public DuplicateSubscriptionException(UUID customerId) {
        super("Customer " + customerId + " already has a non-canceled Subscription");
    }
}
