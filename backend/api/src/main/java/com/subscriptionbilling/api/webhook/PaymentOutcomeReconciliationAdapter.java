package com.subscriptionbilling.api.webhook;

import com.subscriptionbilling.billingjob.reconciliation.PaymentOutcomeReconciler;
import com.subscriptionbilling.billingjob.reconciliation.ReconciliationOutcome;
import com.subscriptionbilling.webhooks.dispatch.PaymentOutcomeReconciliationPort;
import com.subscriptionbilling.webhooks.ingestion.ConflictingPaymentOutcomeException;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * This application's implementation of the webhooks module's {@link
 * PaymentOutcomeReconciliationPort} contract, backed by {@link
 * PaymentOutcomeReconciler}. Lives here rather than in billing-job so that module's own
 * narrow test contexts never need to satisfy the webhooks module's own eager
 * dependencies just to boot — see {@link SubscriptionDisputeCancellationAdapter}'s
 * Javadoc for the equivalent reasoning behind its lazy injection.
 *
 * <p>{@code paymentOutcomeReconciler} is injected lazily for the same reason {@link
 * SubscriptionDisputeCancellationAdapter} lazily injects {@code SubscriptionService}.
 */
@Component
public class PaymentOutcomeReconciliationAdapter implements PaymentOutcomeReconciliationPort {

    private final PaymentOutcomeReconciler paymentOutcomeReconciler;

    public PaymentOutcomeReconciliationAdapter(@Lazy PaymentOutcomeReconciler paymentOutcomeReconciler) {
        this.paymentOutcomeReconciler = paymentOutcomeReconciler;
    }

    @Override
    public void reconcileSucceeded(UUID subscriptionId, String gatewayReference, Instant occurredAt) {
        rejectIfConflicting(paymentOutcomeReconciler.reconcileSucceeded(subscriptionId, gatewayReference, occurredAt),
                gatewayReference, "succeeded");
    }

    @Override
    public void reconcileFailed(UUID subscriptionId, String gatewayReference, Instant occurredAt) {
        rejectIfConflicting(paymentOutcomeReconciler.reconcileFailed(subscriptionId, gatewayReference, occurredAt),
                gatewayReference, "failed");
    }

    private void rejectIfConflicting(ReconciliationOutcome outcome, String gatewayReference, String reportedOutcome) {
        if (outcome == ReconciliationOutcome.CONFLICTING_OUTCOME) {
            throw new ConflictingPaymentOutcomeException(
                    "Webhook reports " + reportedOutcome + " for gateway reference " + gatewayReference
                            + ", but a conflicting outcome is already recorded");
        }
    }
}
