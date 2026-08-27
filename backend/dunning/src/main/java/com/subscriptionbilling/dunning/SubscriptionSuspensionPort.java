package com.subscriptionbilling.dunning;

import java.time.LocalDate;
import java.util.UUID;

/**
 * The application interface this module suspends a Subscription and schedules its
 * Dunning retries through, so it never depends on billing-core's Subscription aggregate
 * or persistence directly. An implementation is expected to be idempotent-adjacent only
 * insofar as it enforces the originating-state rule already owned by whatever performs
 * the suspension; this interface makes no promises beyond "the identified Subscription
 * is suspended" / "the identified Subscription's next retry is scheduled."
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

    /**
     * Schedules the identified Subscription's next Dunning retry by moving its due
     * date, so the billing job's existing due-date query picks it up with no new query
     * needed.
     *
     * @param subscriptionId the Subscription to schedule the retry for
     * @param retryDueDate   the next Dunning retry date
     */
    void scheduleRetry(UUID subscriptionId, LocalDate retryDueDate);
}
