package com.subscriptionbilling.billingcore.subscription;

import java.util.UUID;

/**
 * Thrown for both "no such Subscription" and "exists, but belongs to a different
 * Customer" — deliberately the same exception for both: distinguishing
 * them at the HTTP layer (404 vs. 403) would let a caller enumerate which Subscription
 * IDs exist, which the ADR names explicitly as something a valid token must never leak.
 * The two cases are equally "not yours" from the caller's point of view.
 */
public class SubscriptionAccessDeniedException extends RuntimeException {

    public SubscriptionAccessDeniedException(UUID subscriptionId) {
        super("Subscription " + subscriptionId + " does not belong to the authenticated Customer");
    }
}
