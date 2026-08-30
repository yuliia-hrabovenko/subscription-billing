package com.subscriptionbilling.billingjob.invoicing;

import com.subscriptionbilling.billingjob.anchor.AnchorDate;
import com.subscriptionbilling.billingjob.anchor.BillingCycleAdvancePort;
import com.subscriptionbilling.billingjob.charge.ChargeableSubscription;
import com.subscriptionbilling.billingjob.dunning.DunningHandoff;
import com.subscriptionbilling.billingjob.dunning.DunningRetryOutcome;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The single place a resolved charge outcome is turned into Invoice/PaymentAttempt
 * persistence and Subscription state, shared by every trigger that resolves one: {@link
 * com.subscriptionbilling.billingjob.BillingJobRunner}'s scheduled charge, {@link
 * com.subscriptionbilling.billingjob.dunning.DunningRetryCharge}'s retry attempt, and a
 * payment-succeeded/failed webhook's reconciliation — so none of them records different
 * bookkeeping for what is otherwise the same outcome.
 */
@Component
public class ChargeOutcomeApplier {

    private final ChargeRecordingPort chargeRecordingPort;
    private final BillingCycleAdvancePort billingCycleAdvancePort;
    private final DunningHandoff dunningHandoff;

    @Autowired
    public ChargeOutcomeApplier(ChargeRecordingPort chargeRecordingPort, BillingCycleAdvancePort billingCycleAdvancePort,
                                 DunningHandoff dunningHandoff) {
        this.chargeRecordingPort = chargeRecordingPort;
        this.billingCycleAdvancePort = billingCycleAdvancePort;
        this.dunningHandoff = dunningHandoff;
    }

    /**
     * Records a successful charge and advances {@code due_date} to the next Billing
     * Cycle, clamped via {@link AnchorDate}. For a Dunning retry's success, {@link
     * BillingCycleAdvancePort}'s implementation also restores the Subscription to
     * {@code active}, decided from its own persisted state rather than anything passed
     * in here.
     *
     * @param subscriptionId       the Subscription charged
     * @param chargeable           the resolved charge details this success is for
     * @param gatewayTransactionId the gateway's reference for the completed charge,
     *                             recorded on the PaymentAttempt
     * @param attemptedAt          the instant the gateway resolved the charge
     * @param correlationId        the triggering caller's correlation id, carried onto
     *                             the resulting {@code AuditLogEntry} if this success
     *                             transitions the Subscription's state
     */
    public void applySuccess(UUID subscriptionId, ChargeableSubscription chargeable, String gatewayTransactionId,
                              Instant attemptedAt, String correlationId) {
        chargeRecordingPort.recordSuccessfulCharge(subscriptionId, chargeable.billingPeriod(),
                chargeable.priceVersionId(), gatewayTransactionId, attemptedAt);
        LocalDate nextDueDate = new AnchorDate(chargeable.anchorDayOfMonth()).next(chargeable.billingPeriod());
        billingCycleAdvancePort.advanceDueDate(subscriptionId, nextDueDate, correlationId);
    }

    /**
     * Records a first-attempt charge failure (a renewal or Trial-conversion charge, not
     * a Dunning retry) and hands off to {@link DunningHandoff#onChargeFailed}, which
     * suspends the Subscription and schedules its day-1 retry.
     *
     * @param subscriptionId   the Subscription the failed charge was attempted against
     * @param chargeable       the resolved charge details this failure is for
     * @param gatewayReference the gateway's reference for the declined attempt, or null
     *                         if the gateway didn't supply one
     * @param attemptedAt      the instant the gateway resolved the charge
     * @param correlationId    the triggering caller's correlation id, carried onto the
     *                         resulting suspension's {@code AuditLogEntry}
     */
    public void applyFirstFailure(UUID subscriptionId, ChargeableSubscription chargeable, String gatewayReference,
                                   Instant attemptedAt, String correlationId) {
        UUID invoiceId = chargeRecordingPort.recordFailedCharge(subscriptionId, chargeable.billingPeriod(),
                chargeable.priceVersionId(), gatewayReference, attemptedAt);
        dunningHandoff.onChargeFailed(
                subscriptionId, invoiceId, chargeable.billingPeriod(), attemptedAt, correlationId);
    }

    /**
     * Records a failed scheduled or self-service Dunning retry, increments the Invoice's
     * retry counter, and hands off to {@link DunningHandoff#onRetryFailed} to either
     * reschedule the next offset or cancel the Subscription once the bound is reached.
     *
     * @param subscriptionId   the Subscription the failed retry was attempted against
     * @param chargeable       the resolved charge details this failure is for
     * @param gatewayReference the gateway's reference for the declined attempt, or null
     *                         if the gateway didn't supply one
     * @param attemptedAt      the instant the gateway resolved the charge
     * @param correlationId    the triggering caller's correlation id, carried onto a
     *                         resulting cancellation's {@code AuditLogEntry}
     * @return whether the retry bound was reached (the Subscription was canceled) or the
     *         next Dunning offset was scheduled
     */
    public DunningRetryOutcome applyRetryFailure(UUID subscriptionId, ChargeableSubscription chargeable,
                                                  String gatewayReference, Instant attemptedAt, String correlationId) {
        UUID invoiceId = chargeRecordingPort.recordFailedCharge(subscriptionId, chargeable.billingPeriod(),
                chargeable.priceVersionId(), gatewayReference, attemptedAt);
        DunningRetryState retryState = chargeRecordingPort.recordRetryAttempt(invoiceId);
        return dunningHandoff.onRetryFailed(subscriptionId, invoiceId, retryState, attemptedAt, correlationId);
    }
}
