package com.subscriptionbilling.dunning;

import java.util.UUID;

/**
 * The application interface this module suspends a Subscription through, so it never
 * depends on billing-core's Subscription aggregate or persistence directly. An
 * implementation is expected to be idempotent-adjacent only insofar as it enforces the
 * originating-state rule already owned by whatever performs the suspension; this
 * interface makes no promises beyond "the identified Subscription is suspended."
 */
public interface SubscriptionSuspensionPort {

    /**
     * Suspends the identified Subscription.
     *
     * @param subscriptionId the Subscription to suspend
     * @param correlationId  a caller-supplied identifier for tracing this suspension
     *                       back to what triggered it
     */
    void suspend(UUID subscriptionId, String correlationId);
}
