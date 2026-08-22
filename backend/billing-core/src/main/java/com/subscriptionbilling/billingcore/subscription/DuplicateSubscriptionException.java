package com.subscriptionbilling.billingcore.subscription;

import java.util.UUID;

/**
 * A signup request was made for a Customer who already has a non-{@code canceled}
 * Subscription. Thrown to enforce the rule that a Customer may hold at most one
 * non-{@code canceled} Subscription at a time — multiple Subscriptions over a Customer's
 * lifetime are fine (re-subscribing after a cancellation), concurrent ones are not.
 *
 * <p>Only reachable when the signup request identified an existing Customer (a bearer
 * token was presented); a brand-new Customer can never already have a Subscription.
 */
public class DuplicateSubscriptionException extends RuntimeException {

    public DuplicateSubscriptionException(UUID customerId) {
        super("Customer " + customerId + " already has a non-canceled Subscription");
    }
}
