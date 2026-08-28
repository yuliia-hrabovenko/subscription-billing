package com.subscriptionbilling.webhooks.dispatch;

import java.util.UUID;

/**
 * The application interface a dispute-opened webhook event cancels the identified
 * Subscription through, so this module never depends on billing-core's Subscription
 * aggregate or persistence directly.
 */
public interface DisputeCancellationPort {

    /**
     * Cancels the identified Subscription because a charge against it was disputed.
     *
     * @param subscriptionId the Subscription to cancel
     * @param correlationId  a caller-supplied identifier for tracing this cancellation
     *                       back to the triggering webhook event
     */
    void cancelForDispute(UUID subscriptionId, String correlationId);
}
