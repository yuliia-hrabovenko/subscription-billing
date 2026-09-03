package com.subscriptionbilling.billingjob.dunning;

import com.subscriptionbilling.billingjob.ChargeTrigger;
import com.subscriptionbilling.billingjob.charge.ChargeableSubscription;
import com.subscriptionbilling.billingjob.gateway.ChargeResult;
import com.subscriptionbilling.billingjob.gateway.PaymentGatewayClient;
import com.subscriptionbilling.billingjob.invoicing.ChargeOutcomeApplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
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
    private final ChargeOutcomeApplier chargeOutcomeApplier;

    @Autowired
    public DunningRetryCharge(PaymentGatewayClient paymentGatewayClient, ChargeOutcomeApplier chargeOutcomeApplier) {
        this.paymentGatewayClient = paymentGatewayClient;
        this.chargeOutcomeApplier = chargeOutcomeApplier;
    }

    /**
     * Charges {@code chargeable}'s card-on-file as a Dunning retry Payment Attempt
     * against its already-existing Invoice, and applies whichever outcome the gateway
     * resolves via {@link com.subscriptionbilling.billingjob.invoicing.ChargeOutcomeApplier}:
     * a success restores the Subscription to {@code active} and advances {@code due_date}
     * to the ordinary next Billing Cycle; a decline records the failed attempt,
     * increments the Invoice's retry counter, and hands off to {@link
     * DunningHandoff#onRetryFailed} to either reschedule the next offset or cancel the
     * Subscription once the bound is reached; a transient gateway/network failure
     * records nothing and consumes no retry slot.
     *
     * @param subscriptionId the Subscription to charge
     * @param chargeable     the resolved charge details for {@code subscriptionId}'s
     *                       Dunning retry (its {@code billingPeriod} must be the
     *                       original failed Billing Cycle, not a retry offset date)
     * @param attemptedAt    the instant this charge attempt is made
     * @param correlationId  the triggering caller's correlation id, carried onto any
     *                       resulting {@code AuditLogEntry}
     * @param trigger        who this attempt was triggered by (the billing job's
     *                       scheduled loop or a self-service retry), carried onto a
     *                       resulting recovery's or cancellation's {@code AuditLogEntry}
     *                       attribution
     * @return the resolved outcome
     * @throws com.subscriptionbilling.billingjob.invoicing.ChargeAlreadyRecordedException
     *         if recording this attempt lost a race to a concurrent recording of the
     *         same Invoice
     */
    public DunningRetryChargeResult attempt(UUID subscriptionId, ChargeableSubscription chargeable, Instant attemptedAt,
                                             String correlationId, ChargeTrigger trigger) {
        ChargeResult result = paymentGatewayClient.charge(chargeable.paymentMethodToken(), chargeable.providerCustomerId(), chargeable.amount());
        return switch (result) {
            case ChargeResult.Succeeded succeeded -> {
                chargeOutcomeApplier.applySuccess(subscriptionId, chargeable, succeeded.gatewayTransactionId(),
                        attemptedAt, correlationId, trigger);
                yield new DunningRetryChargeResult.Recovered(succeeded.gatewayTransactionId());
            }
            case ChargeResult.Declined declined -> {
                DunningRetryOutcome outcome = chargeOutcomeApplier.applyRetryFailure(subscriptionId, chargeable,
                        declined.gatewayReference(), attemptedAt, correlationId, trigger);
                yield new DunningRetryChargeResult.Declined(outcome, declined.reason());
            }
            case ChargeResult.FailedTransiently transientFailure ->
                    new DunningRetryChargeResult.FailedTransiently(transientFailure.reason());
        };
    }
}
