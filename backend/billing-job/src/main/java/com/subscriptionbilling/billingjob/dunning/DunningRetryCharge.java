package com.subscriptionbilling.billingjob.dunning;

import com.subscriptionbilling.billingjob.anchor.AnchorDate;
import com.subscriptionbilling.billingjob.anchor.BillingCycleAdvancePort;
import com.subscriptionbilling.billingjob.charge.ChargeableSubscription;
import com.subscriptionbilling.billingjob.gateway.ChargeResult;
import com.subscriptionbilling.billingjob.gateway.PaymentGatewayClient;
import com.subscriptionbilling.billingjob.invoicing.ChargeRecordingPort;
import com.subscriptionbilling.billingjob.invoicing.DunningRetryState;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The single retry-processing path a Dunning retry Payment Attempt goes through,
 * regardless of what triggered it: {@link com.subscriptionbilling.billingjob.BillingJobRunner}'s
 * scheduled day 1/3/7 loop and a Customer's self-service retry both call {@link #attempt}
 * rather than each recording success/failure bookkeeping themselves, so the two triggers
 * can never drift into different Invoice/Subscription bookkeeping.
 */
@Component
public class DunningRetryCharge {

    private final PaymentGatewayClient paymentGatewayClient;
    private final ChargeRecordingPort chargeRecordingPort;
    private final BillingCycleAdvancePort billingCycleAdvancePort;
    private final DunningHandoff dunningHandoff;

    @Autowired
    public DunningRetryCharge(PaymentGatewayClient paymentGatewayClient, ChargeRecordingPort chargeRecordingPort,
                               BillingCycleAdvancePort billingCycleAdvancePort, DunningHandoff dunningHandoff) {
        this.paymentGatewayClient = paymentGatewayClient;
        this.chargeRecordingPort = chargeRecordingPort;
        this.billingCycleAdvancePort = billingCycleAdvancePort;
        this.dunningHandoff = dunningHandoff;
    }

    /**
     * Charges {@code chargeable}'s card-on-file as a Dunning retry Payment Attempt
     * against its already-existing Invoice, and records whichever outcome the gateway
     * resolves: a success restores the Subscription to {@code active} and advances
     * {@code due_date} to the ordinary next Billing Cycle (via {@link
     * BillingCycleAdvancePort}); a decline records the failed attempt, increments the
     * Invoice's retry counter, and hands off to {@link DunningHandoff#onRetryFailed} to
     * either reschedule the next offset or cancel the Subscription once the bound is
     * reached; a transient gateway/network failure records nothing and consumes no
     * retry slot.
     *
     * @param subscriptionId the Subscription to charge
     * @param chargeable     the resolved charge details for {@code subscriptionId}'s
     *                       Dunning retry (its {@code billingPeriod} must be the
     *                       original failed Billing Cycle, not a retry offset date)
     * @param attemptedAt    the instant this charge attempt is made
     * @return the resolved outcome
     * @throws com.subscriptionbilling.billingjob.invoicing.ChargeAlreadyRecordedException
     *         if recording this attempt lost a race to a concurrent recording of the
     *         same Invoice
     */
    public DunningRetryChargeResult attempt(UUID subscriptionId, ChargeableSubscription chargeable, Instant attemptedAt) {
        ChargeResult result = paymentGatewayClient.charge(chargeable.paymentMethodToken(), chargeable.amount());
        return switch (result) {
            case ChargeResult.Succeeded succeeded -> {
                chargeRecordingPort.recordSuccessfulCharge(subscriptionId, chargeable.billingPeriod(),
                        chargeable.priceVersionId(), succeeded.gatewayTransactionId(), attemptedAt);
                LocalDate nextDueDate = new AnchorDate(chargeable.anchorDayOfMonth()).next(chargeable.billingPeriod());
                billingCycleAdvancePort.advanceDueDate(subscriptionId, nextDueDate, succeeded.gatewayTransactionId());
                yield new DunningRetryChargeResult.Recovered(succeeded.gatewayTransactionId());
            }
            case ChargeResult.Declined declined -> {
                UUID invoiceId = chargeRecordingPort.recordFailedCharge(subscriptionId, chargeable.billingPeriod(),
                        chargeable.priceVersionId(), attemptedAt);
                DunningRetryState retryState = chargeRecordingPort.recordRetryAttempt(invoiceId);
                DunningRetryOutcome outcome = dunningHandoff.onRetryFailed(subscriptionId, invoiceId, retryState, attemptedAt);
                yield new DunningRetryChargeResult.Declined(outcome, declined.reason());
            }
            case ChargeResult.FailedTransiently transientFailure ->
                    new DunningRetryChargeResult.FailedTransiently(transientFailure.reason());
        };
    }
}
