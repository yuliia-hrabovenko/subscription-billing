package com.subscriptionbilling.billingjob.dunning;

import com.subscriptionbilling.billingjob.anchor.BillingCycleAdvancePort;
import com.subscriptionbilling.billingjob.charge.ChargeableSubscription;
import com.subscriptionbilling.billingjob.gateway.ChargeResult;
import com.subscriptionbilling.billingjob.gateway.PaymentGatewayClient;
import com.subscriptionbilling.billingjob.invoicing.ChargeOutcomeApplier;
import com.subscriptionbilling.billingjob.invoicing.ChargeRecordingPort;
import com.subscriptionbilling.billingjob.invoicing.DunningRetryState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit coverage of {@link DunningRetryCharge}, the single retry-processing path both
 * {@link com.subscriptionbilling.billingjob.BillingJobRunner}'s scheduled loop and a
 * self-service retry call through: a success records the charge and advances the
 * Billing Cycle; a decline records the failed attempt, increments the retry counter,
 * and defers to {@link DunningHandoff#onRetryFailed} for reschedule-vs-cancel; a
 * transient failure records nothing.
 */
@ExtendWith(MockitoExtension.class)
class DunningRetryChargeTest {

    private static final Instant ATTEMPTED_AT = Instant.parse("2027-01-08T10:00:00Z");

    @Mock
    private PaymentGatewayClient paymentGatewayClient;
    @Mock
    private ChargeRecordingPort chargeRecordingPort;
    @Mock
    private BillingCycleAdvancePort billingCycleAdvancePort;
    @Mock
    private DunningHandoff dunningHandoff;

    private DunningRetryCharge charge() {
        ChargeOutcomeApplier chargeOutcomeApplier =
                new ChargeOutcomeApplier(chargeRecordingPort, billingCycleAdvancePort, dunningHandoff);
        return new DunningRetryCharge(paymentGatewayClient, chargeOutcomeApplier);
    }

    private ChargeableSubscription retryChargeable(UUID subscriptionId) {
        return new ChargeableSubscription(subscriptionId, "tok_visa", new BigDecimal("19.00"),
                UUID.randomUUID(), LocalDate.of(2027, 1, 1), 1, true);
    }

    @Test
    void aSuccessfulRetryRecordsTheChargeAndAdvancesTheBillingCycle() {
        UUID subscriptionId = UUID.randomUUID();
        ChargeableSubscription chargeable = retryChargeable(subscriptionId);
        when(paymentGatewayClient.charge(chargeable.paymentMethodToken(), chargeable.amount()))
                .thenReturn(new ChargeResult.Succeeded("gw-txn-1"));

        DunningRetryChargeResult result = charge().attempt(subscriptionId, chargeable, ATTEMPTED_AT, "corr-1");

        assertThat(result).isEqualTo(new DunningRetryChargeResult.Recovered("gw-txn-1"));
        verify(chargeRecordingPort).recordSuccessfulCharge(subscriptionId, chargeable.billingPeriod(),
                chargeable.priceVersionId(), "gw-txn-1", ATTEMPTED_AT);
        verify(billingCycleAdvancePort).advanceDueDate(subscriptionId, LocalDate.of(2027, 2, 1), "corr-1");
        verify(dunningHandoff, never()).onRetryFailed(any(), any(), any(), any(), any());
    }

    @Test
    void aDeclinedRetryRecordsTheFailedAttemptIncrementsTheCounterAndReschedulesWhenNotExhausted() {
        UUID subscriptionId = UUID.randomUUID();
        UUID invoiceId = UUID.randomUUID();
        ChargeableSubscription chargeable = retryChargeable(subscriptionId);
        DunningRetryState retryState = new DunningRetryState(Instant.parse("2027-01-01T10:00:00Z"), 1, false);
        when(paymentGatewayClient.charge(any(), any())).thenReturn(new ChargeResult.Declined("card_declined"));
        when(chargeRecordingPort.recordFailedCharge(subscriptionId, chargeable.billingPeriod(),
                chargeable.priceVersionId(), null, ATTEMPTED_AT)).thenReturn(invoiceId);
        when(chargeRecordingPort.recordRetryAttempt(invoiceId)).thenReturn(retryState);
        when(dunningHandoff.onRetryFailed(subscriptionId, invoiceId, retryState, ATTEMPTED_AT, "corr-1"))
                .thenReturn(DunningRetryOutcome.RESCHEDULED);

        DunningRetryChargeResult result = charge().attempt(subscriptionId, chargeable, ATTEMPTED_AT, "corr-1");

        assertThat(result).isEqualTo(new DunningRetryChargeResult.Declined(DunningRetryOutcome.RESCHEDULED, "card_declined"));
        verify(billingCycleAdvancePort, never()).advanceDueDate(any(), any(), any());
    }

    @Test
    void aDeclinedRetryThatExhaustsTheBoundReportsCanceled() {
        UUID subscriptionId = UUID.randomUUID();
        UUID invoiceId = UUID.randomUUID();
        ChargeableSubscription chargeable = retryChargeable(subscriptionId);
        DunningRetryState retryState = new DunningRetryState(Instant.parse("2027-01-01T10:00:00Z"), 3, true);
        when(paymentGatewayClient.charge(any(), any())).thenReturn(new ChargeResult.Declined("card_declined"));
        when(chargeRecordingPort.recordFailedCharge(any(), any(), any(), any(), any())).thenReturn(invoiceId);
        when(chargeRecordingPort.recordRetryAttempt(invoiceId)).thenReturn(retryState);
        when(dunningHandoff.onRetryFailed(subscriptionId, invoiceId, retryState, ATTEMPTED_AT, "corr-1"))
                .thenReturn(DunningRetryOutcome.CANCELED);

        DunningRetryChargeResult result = charge().attempt(subscriptionId, chargeable, ATTEMPTED_AT, "corr-1");

        assertThat(result).isEqualTo(new DunningRetryChargeResult.Declined(DunningRetryOutcome.CANCELED, "card_declined"));
    }

    @Test
    void aTransientFailureRecordsNothingAndHandsOffNothing() {
        UUID subscriptionId = UUID.randomUUID();
        ChargeableSubscription chargeable = retryChargeable(subscriptionId);
        when(paymentGatewayClient.charge(any(), any())).thenReturn(new ChargeResult.FailedTransiently("gateway_timeout"));

        DunningRetryChargeResult result = charge().attempt(subscriptionId, chargeable, ATTEMPTED_AT, "corr-1");

        assertThat(result).isEqualTo(new DunningRetryChargeResult.FailedTransiently("gateway_timeout"));
        verify(chargeRecordingPort, never()).recordFailedCharge(any(), any(), any(), any(), any());
        verify(chargeRecordingPort, never()).recordSuccessfulCharge(any(), any(), any(), any(), any());
        verify(dunningHandoff, never()).onRetryFailed(any(), any(), any(), any(), any());
    }
}
