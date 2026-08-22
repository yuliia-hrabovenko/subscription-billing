package com.subscriptionbilling.billingcore.subscription;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface SubscriptionRepository extends JpaRepository<Subscription, UUID> {

    /**
     * Backs Invariant 1's application-layer check (a Customer has at most one
     * non-{@code canceled} Subscription at a time): true if the Customer has any
     * Subscription whose state isn't {@code excludedState}. Passing {@link
     * SubscriptionState#CANCELED} as {@code excludedState} is what makes a Customer
     * whose only prior Subscription is {@code canceled} free to sign up again.
     *
     * @param customerId    the Customer to check
     * @param excludedState a state to ignore when checking for an existing Subscription
     * @return true if a Subscription in a state other than {@code excludedState} exists
     *         for this Customer
     */
    boolean existsByCustomerIdAndStateNot(UUID customerId, SubscriptionState excludedState);
}
