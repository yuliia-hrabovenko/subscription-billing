package com.subscriptionbilling.billingjob;

import com.subscriptionbilling.billingjob.anchor.AnchorDate;
import com.subscriptionbilling.billingjob.anchor.BillingCycleAdvancePort;
import com.subscriptionbilling.billingjob.charge.ChargeableSubscription;
import com.subscriptionbilling.billingjob.charge.ChargeableSubscriptionPort;
import com.subscriptionbilling.billingjob.due.DueSubscriptionsPort;
import com.subscriptionbilling.billingjob.dunning.DunningHandoff;
import com.subscriptionbilling.billingjob.gateway.ChargeResult;
import com.subscriptionbilling.billingjob.gateway.PaymentGatewayClient;
import com.subscriptionbilling.billingjob.invoicing.ChargeRecordingPort;
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
 * Entry point for the daily billing job. Exposes a single public, directly-invokable
 * method so a Kubernetes CronJob, a manual operator run, and a test all go through the
 * same code path — none of them go through a {@code @Scheduled} annotation.
 *
 * <p>For every Subscription {@link DueSubscriptionsPort} selects, charges it through
 * {@link PaymentGatewayClient}:
 * <ul>
 *     <li>Success: records the Invoice/PaymentAttempt via {@link ChargeRecordingPort} and
 *     advances {@code due_date} via {@link BillingCycleAdvancePort} using {@link
 *     AnchorDate}'s clamping rule.
 *     <li>Decline: records a failed PaymentAttempt against the same Invoice (created on
 *     this first attempt if none exists yet) and hands off to {@link DunningHandoff}.
 *     Anchor Date and {@code due_date} are left untouched.
 *     <li>Transient/infra failure: none of the above — no PaymentAttempt, no Dunning
 *     hand-off, no state change — so a gateway timeout is never misclassified as a
 *     business decline. Retrying it is Payment Gateway Integration's concern, not this
 *     runner's.
 * </ul>
 *
 * <p>One Subscription's charge attempt failing unexpectedly (a port throwing rather
 * than the gateway resolving a decline or transient failure) is logged, counted against
 * {@code billing_job_charge_attempt_failures_total}, and skipped rather than aborting the
 * rest of the day's due Subscriptions.
 */
@Component
public class BillingJobRunner {

    private static final Logger log = LoggerFactory.getLogger(BillingJobRunner.class);

    private final DueSubscriptionsPort dueSubscriptionsPort;
    private final ChargeableSubscriptionPort chargeableSubscriptionPort;
    private final PaymentGatewayClient paymentGatewayClient;
    private final ChargeRecordingPort chargeRecordingPort;
    private final BillingCycleAdvancePort billingCycleAdvancePort;
    private final DunningHandoff dunningHandoff;
    private final Clock clock;
    private final Timer jobDuration;
    private final Counter subscriptionsProcessed;
    private final Counter declinedCharges;
    private final Counter chargeAttemptFailures;

    public BillingJobRunner(DueSubscriptionsPort dueSubscriptionsPort,
                             ChargeableSubscriptionPort chargeableSubscriptionPort,
                             PaymentGatewayClient paymentGatewayClient,
                             ChargeRecordingPort chargeRecordingPort,
                             BillingCycleAdvancePort billingCycleAdvancePort,
                             DunningHandoff dunningHandoff,
                             MeterRegistry meterRegistry) {
        this(dueSubscriptionsPort, chargeableSubscriptionPort, paymentGatewayClient, chargeRecordingPort,
                billingCycleAdvancePort, dunningHandoff, meterRegistry, Clock.systemUTC());
    }

    BillingJobRunner(DueSubscriptionsPort dueSubscriptionsPort,
                      ChargeableSubscriptionPort chargeableSubscriptionPort,
                      PaymentGatewayClient paymentGatewayClient,
                      ChargeRecordingPort chargeRecordingPort,
                      BillingCycleAdvancePort billingCycleAdvancePort,
                      DunningHandoff dunningHandoff,
                      MeterRegistry meterRegistry,
                      Clock clock) {
        this.dueSubscriptionsPort = dueSubscriptionsPort;
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
        this.chargeAttemptFailures = Counter.builder("billing_job_charge_attempt_failures_total")
                .description("Charge attempts that failed unexpectedly (a port throwing), as opposed to a gateway decline")
                .register(meterRegistry);
    }

    /**
     * Selects every Subscription due for a charge as of today (per {@link
     * DueSubscriptionsPort#findDueSubscriptionIds}) and attempts to charge each one.
     * Timed and counted for the {@code billing_job_duration_seconds}, {@code
     * billing_job_subscriptions_processed_total}, {@code
     * billing_job_declined_charges_total}, and {@code
     * billing_job_charge_attempt_failures_total} Prometheus metrics.
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
        ChargeableSubscription chargeable = chargeableSubscriptionPort.loadForCharge(subscriptionId);
        ChargeResult result = paymentGatewayClient.charge(chargeable.paymentMethodToken(), chargeable.amount());
        Instant attemptedAt = Instant.now(clock);
        switch (result) {
            case ChargeResult.Succeeded succeeded -> handleSuccess(subscriptionId, chargeable, succeeded, attemptedAt);
            case ChargeResult.Declined declined -> handleDecline(subscriptionId, chargeable, declined, attemptedAt);
            case ChargeResult.FailedTransiently transientFailure ->
                    // Never treated as a decline: no PaymentAttempt, no Dunning hand-off,
                    // no state change. Retrying it is Payment Gateway Integration's job.
                    log.info("Charge failed transiently for subscription {}: {}", subscriptionId, transientFailure.reason());
        }
    }

    private void handleSuccess(UUID subscriptionId, ChargeableSubscription chargeable,
                                ChargeResult.Succeeded succeeded, Instant attemptedAt) {
        chargeRecordingPort.recordSuccessfulCharge(subscriptionId, chargeable.billingPeriod(),
                chargeable.priceVersionId(), succeeded.gatewayTransactionId(), attemptedAt);

        LocalDate nextDueDate = new AnchorDate(chargeable.anchorDayOfMonth()).next(chargeable.billingPeriod());
        billingCycleAdvancePort.advanceDueDate(subscriptionId, nextDueDate);

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
