package com.subscriptionbilling.billingjob.reconciliation;

import com.subscriptionbilling.billingjob.ChargeTrigger;
import com.subscriptionbilling.billingjob.charge.ChargeableSubscription;
import com.subscriptionbilling.billingjob.charge.ChargeableSubscriptionPort;
import com.subscriptionbilling.billingjob.invoicing.ChargeAlreadyRecordedException;
import com.subscriptionbilling.billingjob.invoicing.ChargeOutcomeApplier;
import com.subscriptionbilling.billingjob.invoicing.ChargeRecordingPort;
import com.subscriptionbilling.billingjob.invoicing.RecordedChargeOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Reconciles a payment-succeeded/failed gateway webhook event against whatever the
 * synchronous charge path (billing job or self-service retry) already recorded — an
 * idempotent reliability backstop, not a second implementation of "what happens when a
 * charge succeeds/fails": every outcome it applies goes through the same {@link
 * ChargeOutcomeApplier} the synchronous path uses.
 *
 * <p>Correlation is by gateway reference, not by Subscription: a sync-then-webhook race
 * (the gateway resolving the charge, then also delivering a webhook reporting the same
 * outcome) is detected by {@link ChargeRecordingPort#findRecordedOutcome} finding the
 * PaymentAttempt the synchronous path already recorded, independent of {@code
 * WebhookEventId} dedupe (which only catches a literal redelivery of the same webhook
 * event, not this race).
 */
@Component
public class PaymentOutcomeReconciler {

    private static final Logger log = LoggerFactory.getLogger(PaymentOutcomeReconciler.class);

    private final ChargeRecordingPort chargeRecordingPort;
    private final ChargeableSubscriptionPort chargeableSubscriptionPort;
    private final ChargeOutcomeApplier chargeOutcomeApplier;

    @Autowired
    public PaymentOutcomeReconciler(ChargeRecordingPort chargeRecordingPort,
                                     ChargeableSubscriptionPort chargeableSubscriptionPort,
                                     ChargeOutcomeApplier chargeOutcomeApplier) {
        this.chargeRecordingPort = chargeRecordingPort;
        this.chargeableSubscriptionPort = chargeableSubscriptionPort;
        this.chargeOutcomeApplier = chargeOutcomeApplier;
    }

    /**
     * Reconciles a payment-succeeded webhook event.
     *
     * @param subscriptionId  the Subscription the event's charge was attempted against
     * @param gatewayReference the gateway's reference for the successful charge — this
     *                          event's correlation key
     * @param occurredAt      the instant this reconciliation is being applied
     * @return what happened: applied, already recorded, or a conflicting prior outcome
     */
    public ReconciliationOutcome reconcileSucceeded(UUID subscriptionId, String gatewayReference, Instant occurredAt) {
        return reconcile(subscriptionId, gatewayReference, RecordedChargeOutcome.SUCCEEDED, occurredAt);
    }

    /**
     * Reconciles a payment-failed webhook event.
     *
     * @param subscriptionId  the Subscription the event's charge was attempted against
     * @param gatewayReference the gateway's reference for the declined charge — this
     *                          event's correlation key
     * @param occurredAt      the instant this reconciliation is being applied
     * @return what happened: applied, already recorded, or a conflicting prior outcome
     */
    public ReconciliationOutcome reconcileFailed(UUID subscriptionId, String gatewayReference, Instant occurredAt) {
        return reconcile(subscriptionId, gatewayReference, RecordedChargeOutcome.FAILED, occurredAt);
    }

    private ReconciliationOutcome reconcile(UUID subscriptionId, String gatewayReference,
                                             RecordedChargeOutcome reportedOutcome, Instant occurredAt) {
        Optional<RecordedChargeOutcome> existing = chargeRecordingPort.findRecordedOutcome(gatewayReference);
        if (existing.isPresent()) {
            if (existing.get() == reportedOutcome) {
                log.info("Payment outcome {} for gateway reference {} already recorded; no-op",
                        reportedOutcome, gatewayReference);
                return ReconciliationOutcome.ALREADY_RECORDED;
            }
            log.warn("Conflicting payment outcome for gateway reference {}: webhook reports {} but {} is already "
                    + "recorded", gatewayReference, reportedOutcome, existing.get());
            return ReconciliationOutcome.CONFLICTING_OUTCOME;
        }

        try {
            applyFresh(subscriptionId, gatewayReference, reportedOutcome, occurredAt);
            return ReconciliationOutcome.APPLIED;
        } catch (ChargeAlreadyRecordedException lostRace) {
            // The synchronous path recorded this cycle's outcome between our lookup above
            // and this apply call -- not a failure, just the same race BillingJobRunner's
            // own pre-check/record window can lose.
            log.info("Lost the race reconciling gateway reference {}: already recorded by another run",
                    gatewayReference, lostRace);
            return ReconciliationOutcome.ALREADY_RECORDED;
        }
    }

    private void applyFresh(UUID subscriptionId, String gatewayReference, RecordedChargeOutcome reportedOutcome,
                             Instant occurredAt) {
        ChargeableSubscription chargeable = chargeableSubscriptionPort.loadForCharge(subscriptionId);
        // The gateway reference doubles as this event's correlation id -- there is no
        // job run or request to inherit one from, and it's already this event's own
        // correlation key (see class Javadoc).
        if (reportedOutcome == RecordedChargeOutcome.SUCCEEDED) {
            chargeOutcomeApplier.applySuccess(
                    subscriptionId, chargeable, gatewayReference, occurredAt, gatewayReference, ChargeTrigger.SYSTEM);
        } else if (chargeable.dunningRetry()) {
            chargeOutcomeApplier.applyRetryFailure(
                    subscriptionId, chargeable, gatewayReference, occurredAt, gatewayReference, ChargeTrigger.SYSTEM);
        } else {
            chargeOutcomeApplier.applyFirstFailure(
                    subscriptionId, chargeable, gatewayReference, occurredAt, gatewayReference);
        }
    }
}
