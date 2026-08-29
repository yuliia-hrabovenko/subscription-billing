package com.subscriptionbilling.webhooks.dispatch;

import com.subscriptionbilling.webhooks.ingestion.ConflictingPaymentOutcomeException;

import java.time.Instant;
import java.util.UUID;

/**
 * The application interface a payment-succeeded/failed webhook event reconciles the
 * identified Subscription's Payment Attempt outcome through, so this module never
 * depends on billing-job's charge-recording/Dunning internals directly. An
 * implementation applies the outcome only if it isn't already recorded for {@code
 * gatewayReference} — a redundant sync-then-webhook report is a no-op, and a conflicting
 * one is rejected — reusing the same outcome logic the synchronous charge path uses
 * rather than reimplementing it.
 */
public interface PaymentOutcomeReconciliationPort {

    /**
     * Reconciles a payment-succeeded event.
     *
     * @param subscriptionId   the Subscription the event's charge was attempted against
     * @param gatewayReference the gateway's reference for the successful charge — this
     *                         event's correlation key to the specific Payment Attempt
     * @param occurredAt       the instant this reconciliation is being applied
     * @throws ConflictingPaymentOutcomeException if a Payment Attempt for {@code
     *         gatewayReference} is already recorded {@code failed}
     */
    void reconcileSucceeded(UUID subscriptionId, String gatewayReference, Instant occurredAt);

    /**
     * Reconciles a payment-failed event.
     *
     * @param subscriptionId   the Subscription the event's charge was attempted against
     * @param gatewayReference the gateway's reference for the declined charge — this
     *                         event's correlation key to the specific Payment Attempt
     * @param occurredAt       the instant this reconciliation is being applied
     * @throws ConflictingPaymentOutcomeException if a Payment Attempt for {@code
     *         gatewayReference} is already recorded {@code succeeded}
     */
    void reconcileFailed(UUID subscriptionId, String gatewayReference, Instant occurredAt);
}
