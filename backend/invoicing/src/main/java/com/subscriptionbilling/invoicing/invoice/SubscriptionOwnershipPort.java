package com.subscriptionbilling.invoicing.invoice;

import java.util.UUID;

/**
 * The seam an Invoice read's ownership check is resolved through, so this module never
 * depends on billing-core's Subscription/Customer persistence directly. An Invoice has
 * no {@code customerId} of its own (ownership resolves through the
 * Subscription it belongs to).
 */
public interface SubscriptionOwnershipPort {

    /**
     * @param subscriptionId the Subscription an Invoice read is being checked against
     * @param customerId     the Customer the caller's bearer token identifies
     * @return true only if {@code subscriptionId} identifies a Subscription belonging to
     *         {@code customerId}; false both when it belongs to a different Customer and
     *         when no such Subscription exists
     */
    boolean isOwnedByCustomer(UUID subscriptionId, UUID customerId);
}
