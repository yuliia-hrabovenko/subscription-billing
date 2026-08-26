package com.subscriptionbilling.billingjob;

import com.subscriptionbilling.billingjob.anchor.AnchorDate;
import com.subscriptionbilling.billingjob.anchor.BillingCycleAdvancePort;
import com.subscriptionbilling.billingjob.charge.ChargeableSubscription;
import com.subscriptionbilling.billingjob.charge.ChargeableSubscriptionPort;
import com.subscriptionbilling.billingjob.due.DueSubscriptionsPort;
import com.subscriptionbilling.billingjob.dunning.DunningHandoff;
import com.subscriptionbilling.billingjob.gateway.ChargeResult;
import com.subscriptionbilling.billingjob.gateway.PaymentGatewayClient;
import com.subscriptionbilling.billingjob.invoicing.ChargeAlreadyRecordedException;
import com.subscriptionbilling.billingjob.invoicing.ChargeRecordingPort;
import com.subscriptionbilling.billingjob.planchange.PendingPlanChangeOutcome;
import com.subscriptionbilling.billingjob.planchange.PendingPlanChangePort;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
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
 * Subscription already invoiced for this Billing Cycle is skipped, and the charge goes
 * through {@link PaymentGatewayClient} — a Trial's auto-conversion charge follows the
 * exact same path as an ordinary renewal:
 * <ul>
 *     <li>Success: records the Invoice/PaymentAttempt via {@link ChargeRecordingPort} and
 *     advances {@code due_date} via {@link BillingCycleAdvancePort} using {@link
 *     AnchorDate}'s clamping rule.
 *     <li>Decline: records a failed PaymentAttempt and hands off to {@link DunningHandoff};
 *     the Anchor Date and {@code due_date} are left unchanged.
 *     <li>Transient/infra failure: no PaymentAttempt, no Dunning hand-off, no state
 *     change — never treated as a decline.
 * </ul>
 *
 * <p>{@link ChargeAlreadyRecordedException} from a concurrent-record race is treated the
 * same as an already-invoiced skip, not a failure. Any other unexpected failure for one
 * Subscription is logged and counted, not allowed to stop the rest of the run.
 */
@Component
public class BillingJobRunner {

    private static final Logger log = LoggerFactory.getLogger(BillingJobRunner.class);

    private final DueSubscriptionsPort dueSubscriptionsPort;
    private final PendingPlanChangePort pendingPlanChangePort;
    private final ChargeableSubscriptionPort chargeableSubscriptionPort;
    private final PaymentGatewayClient paymentGatewayClient;
    private final ChargeRecordingPort chargeRecordingPort;
    private final BillingCycleAdvancePort billingCycleAdvancePort;
    private final DunningHandoff dunningHandoff;
    private final Clock clock;
    private final Timer jobDuration;
    private final Counter subscriptionsProcessed;
    private final Counter declinedCharges;
    private final Counter duplicateChargesSkipped;
    private final Counter chargeAttemptFailures;

    public BillingJobRunner(DueSubscriptionsPort dueSubscriptionsPort,
                             PendingPlanChangePort pendingPlanChangePort,
                             ChargeableSubscriptionPort chargeableSubscriptionPort,
                             PaymentGatewayClient paymentGatewayClient,
                             ChargeRecordingPort chargeRecordingPort,
                             BillingCycleAdvancePort billingCycleAdvancePort,
                             DunningHandoff dunningHandoff,
                             MeterRegistry meterRegistry) {
        this(dueSubscriptionsPort, pendingPlanChangePort, chargeableSubscriptionPort, paymentGatewayClient,
                chargeRecordingPort, billingCycleAdvancePort, dunningHandoff, meterRegistry, Clock.systemUTC());
    }

    BillingJobRunner(DueSubscriptionsPort dueSubscriptionsPort,
                      PendingPlanChangePort pendingPlanChangePort,
                      ChargeableSubscriptionPort chargeableSubscriptionPort,
                      PaymentGatewayClient paymentGatewayClient,
                      ChargeRecordingPort chargeRecordingPort,
                      BillingCycleAdvancePort billingCycleAdvancePort,
                      DunningHandoff dunningHandoff,
                      MeterRegistry meterRegistry,
                      Clock clock) {
        this.dueSubscriptionsPort = dueSubscriptionsPort;
        this.pendingPlanChangePort = pendingPlanChangePort;
        this.chargeableSubscriptionPort = chargeableSubscriptionPort;
        this.paymentGatewayClient = paymentGatewayClient;
        this.chargeRecordingPort = chargeRecordingPort;
        this.billingCycleAdvancePort = billingCycleAdvancePort;
        this.dunningHandoff = dunningHandoff;
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
    }

    /**
     * Selects every Subscription due for a charge as of today and attempts to charge
     * each one.
     *
     * @return the ids of every Subscription selected as due, regardless of whether its
     *         charge attempt succeeded
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
            return dueSubscriptionIds;
        });
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
        if (chargeRecordingPort.invoiceAlreadyRecorded(subscriptionId, chargeable.billingPeriod())) {
            // A prior run (this one resumed after a crash, or another overlapping
            // instance) already recorded an outcome for this cycle -- never call the
            // gateway again for it.
            skipAsDuplicate(subscriptionId, "billing period " + chargeable.billingPeriod() + " is already invoiced");
            return;
        }

        ChargeResult result = paymentGatewayClient.charge(chargeable.paymentMethodToken(), chargeable.amount());
        Instant attemptedAt = Instant.now(clock);
        try {
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
        chargeRecordingPort.recordSuccessfulCharge(subscriptionId, chargeable.billingPeriod(),
                chargeable.priceVersionId(), succeeded.gatewayTransactionId(), attemptedAt);

        LocalDate nextDueDate = new AnchorDate(chargeable.anchorDayOfMonth()).next(chargeable.billingPeriod());
        billingCycleAdvancePort.advanceDueDate(subscriptionId, nextDueDate, succeeded.gatewayTransactionId());

        subscriptionsProcessed.increment();
    }

    private void handleDecline(UUID subscriptionId, ChargeableSubscription chargeable,
                                ChargeResult.Declined declined, Instant attemptedAt) {
        UUID invoiceId = chargeRecordingPort.recordFailedCharge(subscriptionId, chargeable.billingPeriod(),
                chargeable.priceVersionId(), attemptedAt);
        dunningHandoff.onChargeFailed(subscriptionId, invoiceId);
        declinedCharges.increment();
        log.info("Charge declined for subscription {}: {}", subscriptionId, declined.reason());
    }
}
