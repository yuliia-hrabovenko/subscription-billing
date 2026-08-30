package com.subscriptionbilling.billingjob;

import com.subscriptionbilling.billingjob.charge.ChargeableSubscription;
import com.subscriptionbilling.billingjob.charge.ChargeableSubscriptionPort;
import com.subscriptionbilling.billingjob.due.DueSubscriptionsPort;
import com.subscriptionbilling.billingjob.dunning.DunningRetryCharge;
import com.subscriptionbilling.billingjob.dunning.DunningRetryChargeResult;
import com.subscriptionbilling.billingjob.dunning.DunningRetryOutcome;
import com.subscriptionbilling.billingjob.gateway.ChargeResult;
import com.subscriptionbilling.billingjob.gateway.PaymentGatewayClient;
import com.subscriptionbilling.billingjob.invoicing.ChargeAlreadyRecordedException;
import com.subscriptionbilling.billingjob.invoicing.ChargeOutcomeApplier;
import com.subscriptionbilling.billingjob.invoicing.ChargeRecordingPort;
import com.subscriptionbilling.billingjob.planchange.PendingPlanChangeOutcome;
import com.subscriptionbilling.billingjob.planchange.PendingPlanChangePort;
import com.subscriptionbilling.billingjob.trial.TrialEndingSoonPort;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Entry point for the daily billing job. Exposes one public, directly-invokable method
 * so a Kubernetes CronJob, a manual operator run, and a test all go through the same
 * code path.
 *
 * <p>For each Subscription {@link DueSubscriptionsPort} selects: a pending Plan change is
 * applied first via {@link PendingPlanChangePort}, so any charge reflects the new Plan. A
 * change onto a free Plan ends processing there — no charge is attempted. Otherwise, a
 * {@link ChargeableSubscriptionPort#loadForCharge} call resolves whether this is an
 * ordinary renewal/Trial-conversion charge or a due Dunning retry (a {@code suspended}
 * Subscription — see {@link ChargeableSubscription#dunningRetry()}), which decides which
 * outcome handling below applies; a non-retry Subscription already invoiced for this
 * Billing Cycle is skipped rather than charged again. Either way the charge goes through
 * {@link PaymentGatewayClient}, and the resolved outcome is applied via {@link
 * ChargeOutcomeApplier} — the same component a payment-succeeded/failed webhook's
 * reconciliation calls into, so this job and that backstop never record different
 * bookkeeping for the same outcome:
 * <ul>
 *     <li>Success: {@link ChargeOutcomeApplier#applySuccess} records the Invoice/PaymentAttempt
 *     and advances {@code due_date} — for a Dunning retry this same call also restores the
 *     Subscription to {@code active}.
 *     <li>Decline (not a retry): {@link ChargeOutcomeApplier#applyFirstFailure} records a
 *     failed PaymentAttempt and suspends the Subscription, scheduling its day-1 retry; the
 *     Anchor Date and {@code due_date} are left unchanged.
 * </ul>
 *
 * <p>A due Dunning retry is instead driven entirely through {@link DunningRetryCharge#attempt},
 * shared with the self-service retry-payment endpoint so the two triggers can never
 * record different bookkeeping for the same kind of attempt.
 *
 * <p>{@link ChargeAlreadyRecordedException} from a concurrent-record race is treated the
 * same as an already-invoiced skip, not a failure. Any other unexpected failure for one
 * Subscription is logged and counted, not allowed to stop the rest of the run.
 *
 * <p>As an additional, independent step, every run also scans for trialing Subscriptions
 * approaching their Trial end via {@link TrialEndingSoonPort} and notifies each one not
 * already notified — riding this same daily run rather than a second scheduled trigger.
 * A notification failure for one Subscription is likewise logged and counted, not
 * allowed to stop the rest of the run or the charge step above.
 */
@Component
public class BillingJobRunner {

    private static final Logger log = LoggerFactory.getLogger(BillingJobRunner.class);

    /**
     * How far ahead of a Trial's end the ending-soon scan starts notifying for it. Not
     * specified by any prior source document; an assumption picked for this
     * implementation, revisit if a different lead time is wanted.
     */
    static final Duration TRIAL_ENDING_SOON_LEAD_TIME = Duration.ofDays(3);

    private final DueSubscriptionsPort dueSubscriptionsPort;
    private final PendingPlanChangePort pendingPlanChangePort;
    private final ChargeableSubscriptionPort chargeableSubscriptionPort;
    private final PaymentGatewayClient paymentGatewayClient;
    private final ChargeRecordingPort chargeRecordingPort;
    private final DunningRetryCharge dunningRetryCharge;
    private final ChargeOutcomeApplier chargeOutcomeApplier;
    private final TrialEndingSoonPort trialEndingSoonPort;
    private final Clock clock;
    private final Timer jobDuration;
    private final Counter subscriptionsProcessed;
    private final Counter declinedCharges;
    private final Counter duplicateChargesSkipped;
    private final Counter chargeAttemptFailures;
    private final Counter dunningAttempts;
    private final Counter dunningRecoveries;
    private final Counter dunningExhaustions;
    private final Counter trialEndingSoonNotificationsSent;
    private final Counter trialEndingSoonNotificationFailures;

    @Autowired
    public BillingJobRunner(DueSubscriptionsPort dueSubscriptionsPort,
                             PendingPlanChangePort pendingPlanChangePort,
                             ChargeableSubscriptionPort chargeableSubscriptionPort,
                             PaymentGatewayClient paymentGatewayClient,
                             ChargeRecordingPort chargeRecordingPort,
                             DunningRetryCharge dunningRetryCharge,
                             ChargeOutcomeApplier chargeOutcomeApplier,
                             TrialEndingSoonPort trialEndingSoonPort,
                             MeterRegistry meterRegistry) {
        this(dueSubscriptionsPort, pendingPlanChangePort, chargeableSubscriptionPort, paymentGatewayClient,
                chargeRecordingPort, dunningRetryCharge, chargeOutcomeApplier, trialEndingSoonPort, meterRegistry,
                Clock.systemUTC());
    }

    BillingJobRunner(DueSubscriptionsPort dueSubscriptionsPort,
                      PendingPlanChangePort pendingPlanChangePort,
                      ChargeableSubscriptionPort chargeableSubscriptionPort,
                      PaymentGatewayClient paymentGatewayClient,
                      ChargeRecordingPort chargeRecordingPort,
                      DunningRetryCharge dunningRetryCharge,
                      ChargeOutcomeApplier chargeOutcomeApplier,
                      TrialEndingSoonPort trialEndingSoonPort,
                      MeterRegistry meterRegistry,
                      Clock clock) {
        this.dueSubscriptionsPort = dueSubscriptionsPort;
        this.pendingPlanChangePort = pendingPlanChangePort;
        this.chargeableSubscriptionPort = chargeableSubscriptionPort;
        this.paymentGatewayClient = paymentGatewayClient;
        this.chargeRecordingPort = chargeRecordingPort;
        this.dunningRetryCharge = dunningRetryCharge;
        this.chargeOutcomeApplier = chargeOutcomeApplier;
        this.trialEndingSoonPort = trialEndingSoonPort;
        this.clock = clock;
        this.jobDuration = Timer.builder("billing_job_duration_seconds")
                .description("Wall-clock duration of a single BillingJobRunner#run execution")
                .register(meterRegistry);
        this.subscriptionsProcessed = Counter.builder("billing_job_subscriptions_processed_total")
                .description("Subscriptions successfully charged by a BillingJobRunner#run execution")
                .register(meterRegistry);
        this.declinedCharges = Counter.builder("billing_job_declined_charges_total")
                .description("Charge attempts declined by the gateway and handed off to Dunning")
                .register(meterRegistry);
        this.duplicateChargesSkipped = Counter.builder("billing_job_duplicate_charge_skipped_total")
                .description("Charge attempts skipped because this Billing Cycle was already invoiced by a prior "
                        + "or concurrent run -- not a charge failure")
                .register(meterRegistry);
        this.chargeAttemptFailures = Counter.builder("billing_job_charge_attempt_failures_total")
                .description("Charge attempts that failed unexpectedly (a port throwing), as opposed to a gateway decline")
                .register(meterRegistry);
        this.dunningAttempts = Counter.builder("billing_job_dunning_attempts_total")
                .description("Scheduled Dunning retry charge attempts (day 1/3/7), regardless of outcome")
                .register(meterRegistry);
        this.dunningRecoveries = Counter.builder("billing_job_dunning_recoveries_total")
                .description("Scheduled Dunning retries that succeeded, restoring the Subscription to active")
                .register(meterRegistry);
        this.dunningExhaustions = Counter.builder("billing_job_dunning_exhaustions_total")
                .description("Subscriptions canceled because their Dunning retries were exhausted")
                .register(meterRegistry);
        this.trialEndingSoonNotificationsSent = Counter.builder("billing_job_trial_ending_soon_notifications_sent_total")
                .description("Trial-ending-soon notifications enqueued by a BillingJobRunner#run execution")
                .register(meterRegistry);
        this.trialEndingSoonNotificationFailures = Counter.builder("billing_job_trial_ending_soon_notification_failures_total")
                .description("Trial-ending-soon notification attempts that failed unexpectedly (a port throwing)")
                .register(meterRegistry);
    }

    /**
     * Selects every Subscription due for a charge as of today and attempts to charge
     * each one, then runs the independent trial-ending-soon scan.
     *
     * @return the ids of every Subscription selected as due, regardless of whether its
     *         charge attempt succeeded — never includes a trial-ending-soon candidate
     *         that wasn't also due for a charge
     */
    public List<UUID> run() {
        return jobDuration.record(() -> {
            List<UUID> dueSubscriptionIds = dueSubscriptionsPort.findDueSubscriptionIds(LocalDate.now(clock));
            for (UUID subscriptionId : dueSubscriptionIds) {
                try {
                    chargeOneCycle(subscriptionId);
                } catch (RuntimeException chargeAttemptFailed) {
                    // One Subscription's port call blowing up (missing PriceVersion, a
                    // transient DB error, ...) must not stop every other due
                    // Subscription behind it in the list from being attempted today.
                    log.error("Charge attempt failed unexpectedly for subscription {}", subscriptionId, chargeAttemptFailed);
                    chargeAttemptFailures.increment();
                }
            }
            notifyTrialsEndingSoon();
            return dueSubscriptionIds;
        });
    }

    /**
     * Notifies every trialing Subscription approaching its Trial end that hasn't already
     * been notified. One Subscription's notification attempt throwing is logged and
     * counted, not allowed to stop the rest.
     */
    private void notifyTrialsEndingSoon() {
        Instant leadTimeCutoff = Instant.now(clock).plus(TRIAL_ENDING_SOON_LEAD_TIME);
        for (UUID subscriptionId : trialEndingSoonPort.findTrialsEndingSoon(leadTimeCutoff)) {
            try {
                trialEndingSoonPort.notifyTrialEndingSoon(subscriptionId, Instant.now(clock));
                trialEndingSoonNotificationsSent.increment();
            } catch (RuntimeException notifyFailed) {
                log.error("Trial-ending-soon notification failed unexpectedly for subscription {}", subscriptionId, notifyFailed);
                trialEndingSoonNotificationFailures.increment();
            }
        }
    }

    private void chargeOneCycle(UUID subscriptionId) {
        if (pendingPlanChangePort.applyIfPending(subscriptionId) == PendingPlanChangeOutcome.APPLIED_FREE) {
            // Applying the pending change already moved this Subscription onto a free
            // Plan and cleared its Billing Cycle/due date -- there is nothing left for
            // this cycle to charge.
            log.info("Applied pending plan change to a free Plan for subscription {}; no charge attempted", subscriptionId);
            return;
        }

        ChargeableSubscription chargeable = chargeableSubscriptionPort.loadForCharge(subscriptionId);
        // A Dunning retry's Invoice always already exists for its billing period (it was
        // created by the initial failure this retry is attached to) -- this pre-check
        // only guards a fresh renewal/Trial-conversion cycle against being invoiced twice.
        if (!chargeable.dunningRetry() && chargeRecordingPort.invoiceAlreadyRecorded(subscriptionId, chargeable.billingPeriod())) {
            // A prior run (this one resumed after a crash, or another overlapping
            // instance) already recorded an outcome for this cycle -- never call the
            // gateway again for it.
            skipAsDuplicate(subscriptionId, "billing period " + chargeable.billingPeriod() + " is already invoiced");
            return;
        }

        Instant attemptedAt = Instant.now(clock);
        try {
            if (chargeable.dunningRetry()) {
                handleDunningRetryCharge(subscriptionId, chargeable, attemptedAt);
                return;
            }
            ChargeResult result = paymentGatewayClient.charge(chargeable.paymentMethodToken(), chargeable.amount());
            switch (result) {
                case ChargeResult.Succeeded succeeded -> handleSuccess(subscriptionId, chargeable, succeeded, attemptedAt);
                case ChargeResult.Declined declined -> handleDecline(subscriptionId, chargeable, declined, attemptedAt);
                case ChargeResult.FailedTransiently transientFailure ->
                        // Never treated as a decline: no PaymentAttempt, no Dunning hand-off,
                        // no state change. Retrying it is Payment Gateway Integration's job.
                        log.info("Charge failed transiently for subscription {}: {}", subscriptionId, transientFailure.reason());
            }
        } catch (ChargeAlreadyRecordedException lostRace) {
            // The genuine-concurrent-race backstop: another instance won the create-Invoice
            // race between our pre-check above and this recording call. Not a failure --
            // that other instance's recording is this cycle's outcome of record.
            skipAsDuplicate(subscriptionId, "lost the concurrent-record race: " + lostRace.getMessage());
        }
    }

    private void skipAsDuplicate(UUID subscriptionId, String reason) {
        log.info("Skipping subscription {}: {}", subscriptionId, reason);
        duplicateChargesSkipped.increment();
    }

    private void handleSuccess(UUID subscriptionId, ChargeableSubscription chargeable,
                                ChargeResult.Succeeded succeeded, Instant attemptedAt) {
        chargeOutcomeApplier.applySuccess(subscriptionId, chargeable, succeeded.gatewayTransactionId(), attemptedAt);
        subscriptionsProcessed.increment();
    }

    private void handleDecline(UUID subscriptionId, ChargeableSubscription chargeable,
                                ChargeResult.Declined declined, Instant attemptedAt) {
        chargeOutcomeApplier.applyFirstFailure(subscriptionId, chargeable, declined.gatewayReference(), attemptedAt);
        declinedCharges.increment();
        log.info("Charge declined for subscription {}: {}", subscriptionId, declined.reason());
    }

    /**
     * The due Dunning retry path: delegates the charge attempt and its bookkeeping
     * entirely to {@link DunningRetryCharge#attempt}, shared with the self-service
     * retry-payment endpoint, and only translates the result into this job's own
     * metrics/logging.
     */
    private void handleDunningRetryCharge(UUID subscriptionId, ChargeableSubscription chargeable, Instant attemptedAt) {
        dunningAttempts.increment();
        DunningRetryChargeResult result = dunningRetryCharge.attempt(subscriptionId, chargeable, attemptedAt);
        switch (result) {
            case DunningRetryChargeResult.Recovered recovered -> {
                dunningRecoveries.increment();
                log.info("Dunning retry succeeded for subscription {}: restored to active", subscriptionId);
            }
            case DunningRetryChargeResult.Declined declined -> {
                if (declined.outcome() == DunningRetryOutcome.CANCELED) {
                    dunningExhaustions.increment();
                    log.info("Dunning retries exhausted for subscription {}: canceled", subscriptionId);
                } else {
                    log.info("Dunning retry declined for subscription {}: rescheduled", subscriptionId);
                }
            }
            case DunningRetryChargeResult.FailedTransiently transientFailure ->
                    log.info("Dunning retry charge failed transiently for subscription {}: {}",
                            subscriptionId, transientFailure.reason());
        }
    }
}
