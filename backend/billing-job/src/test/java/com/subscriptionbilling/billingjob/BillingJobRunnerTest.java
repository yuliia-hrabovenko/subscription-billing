package com.subscriptionbilling.billingjob;

import com.subscriptionbilling.billingjob.anchor.BillingCycleAdvancePort;
import com.subscriptionbilling.billingjob.charge.ChargeableSubscription;
import com.subscriptionbilling.billingjob.charge.ChargeableSubscriptionPort;
import com.subscriptionbilling.billingjob.due.DueSubscriptionsPort;
import com.subscriptionbilling.billingjob.dunning.DunningHandoff;
import com.subscriptionbilling.billingjob.gateway.ChargeResult;
import com.subscriptionbilling.billingjob.gateway.PaymentGatewayClient;
import com.subscriptionbilling.billingjob.invoicing.ChargeRecordingPort;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit coverage of {@link BillingJobRunner}: it resolves today's date from its {@link
 * Clock}, drives one charge attempt per Subscription {@link DueSubscriptionsPort}
 * selects for it, and records/advances only on a successful charge. Whether the
 * due-date comparison, charge-detail loading, or the recording/advancing ports'
 * persistence is correct against real data is each port implementation's own contract,
 * covered where that implementation lives.
 */
@ExtendWith(MockitoExtension.class)
class BillingJobRunnerTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 24);
    private static final Clock FIXED_CLOCK = Clock.fixed(TODAY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);

    @Mock
    private DueSubscriptionsPort dueSubscriptionsPort;

    @Mock
    private ChargeableSubscriptionPort chargeableSubscriptionPort;

    @Mock
    private PaymentGatewayClient paymentGatewayClient;

    @Mock
    private ChargeRecordingPort chargeRecordingPort;

    @Mock
    private BillingCycleAdvancePort billingCycleAdvancePort;

    @Mock
    private DunningHandoff dunningHandoff;

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    private BillingJobRunner runner() {
        return new BillingJobRunner(dueSubscriptionsPort, chargeableSubscriptionPort, paymentGatewayClient,
                chargeRecordingPort, billingCycleAdvancePort, dunningHandoff, meterRegistry, FIXED_CLOCK);
    }

    @Test
    void runReturnsExactlyTheSubscriptionIdsThePortSelectsForToday() {
        UUID dueSubscriptionId = UUID.randomUUID();
        when(dueSubscriptionsPort.findDueSubscriptionIds(TODAY)).thenReturn(List.of(dueSubscriptionId));
        when(chargeableSubscriptionPort.loadForCharge(dueSubscriptionId)).thenReturn(chargeable(dueSubscriptionId));
        when(paymentGatewayClient.charge(any(), any())).thenReturn(new ChargeResult.Declined("card_declined"));

        List<UUID> selected = runner().run();

        assertThat(selected).containsExactly(dueSubscriptionId);
    }

    @Test
    void runReturnsAnEmptyListWhenNoSubscriptionIsDue() {
        when(dueSubscriptionsPort.findDueSubscriptionIds(TODAY)).thenReturn(List.of());

        List<UUID> selected = runner().run();

        assertThat(selected).isEmpty();
    }

    @Test
    void aSuccessfulChargeRecordsItAndAdvancesDueDateUsingAnchorDateClamping() {
        UUID subscriptionId = UUID.randomUUID();
        // Jan-31 anchored, currently billing for Jan 31 -- must clamp into Feb 28.
        ChargeableSubscription chargeable = new ChargeableSubscription(
                subscriptionId, "tok_visa", new BigDecimal("19.00"), UUID.randomUUID(),
                LocalDate.of(2026, 1, 31), 31);
        when(dueSubscriptionsPort.findDueSubscriptionIds(TODAY)).thenReturn(List.of(subscriptionId));
        when(chargeableSubscriptionPort.loadForCharge(subscriptionId)).thenReturn(chargeable);
        when(paymentGatewayClient.charge("tok_visa", new BigDecimal("19.00")))
                .thenReturn(new ChargeResult.Succeeded("gw-txn-1"));

        runner().run();

        verify(chargeRecordingPort).recordSuccessfulCharge(
                eq(subscriptionId), eq(LocalDate.of(2026, 1, 31)), eq(chargeable.priceVersionId()),
                eq("gw-txn-1"), eq(FIXED_CLOCK.instant()));
        verify(billingCycleAdvancePort).advanceDueDate(subscriptionId, LocalDate.of(2026, 2, 28));
    }

    @Test
    void aSuccessfulChargeIncrementsTheSubscriptionsProcessedCounter() {
        UUID subscriptionId = UUID.randomUUID();
        when(dueSubscriptionsPort.findDueSubscriptionIds(TODAY)).thenReturn(List.of(subscriptionId));
        when(chargeableSubscriptionPort.loadForCharge(subscriptionId)).thenReturn(chargeable(subscriptionId));
        when(paymentGatewayClient.charge(any(), any())).thenReturn(new ChargeResult.Succeeded("gw-txn-1"));

        runner().run();

        assertThat(meterRegistry.get("billing_job_subscriptions_processed_total").counter().count()).isEqualTo(1.0);
    }

    @Test
    void everyRunRecordsTheJobDurationTimerRegardlessOfOutcome() {
        when(dueSubscriptionsPort.findDueSubscriptionIds(TODAY)).thenReturn(List.of());

        runner().run();

        assertThat(meterRegistry.get("billing_job_duration_seconds").timer().count()).isEqualTo(1L);
    }

    @Test
    void aDeclinedChargeRecordsAFailedAttemptAndHandsOffToDunningWithoutAdvancingTheBillingCycle() {
        UUID subscriptionId = UUID.randomUUID();
        UUID invoiceId = UUID.randomUUID();
        ChargeableSubscription chargeable = chargeable(subscriptionId);
        when(dueSubscriptionsPort.findDueSubscriptionIds(TODAY)).thenReturn(List.of(subscriptionId));
        when(chargeableSubscriptionPort.loadForCharge(subscriptionId)).thenReturn(chargeable);
        when(paymentGatewayClient.charge(any(), any())).thenReturn(new ChargeResult.Declined("card_declined"));
        when(chargeRecordingPort.recordFailedCharge(subscriptionId, chargeable.billingPeriod(),
                chargeable.priceVersionId(), FIXED_CLOCK.instant())).thenReturn(invoiceId);

        runner().run();

        verify(chargeRecordingPort).recordFailedCharge(subscriptionId, chargeable.billingPeriod(),
                chargeable.priceVersionId(), FIXED_CLOCK.instant());
        verify(dunningHandoff).onChargeFailed(subscriptionId, invoiceId);
        verify(chargeRecordingPort, never()).recordSuccessfulCharge(any(), any(), any(), any(), any());
        verify(billingCycleAdvancePort, never()).advanceDueDate(any(), any());
        assertThat(meterRegistry.get("billing_job_subscriptions_processed_total").counter().count()).isEqualTo(0.0);
        assertThat(meterRegistry.get("billing_job_declined_charges_total").counter().count()).isEqualTo(1.0);
    }

    @Test
    void aTransientGatewayFailureNeitherRecordsNorAdvancesNorHandsOffToDunning() {
        UUID subscriptionId = UUID.randomUUID();
        when(dueSubscriptionsPort.findDueSubscriptionIds(TODAY)).thenReturn(List.of(subscriptionId));
        when(chargeableSubscriptionPort.loadForCharge(subscriptionId)).thenReturn(chargeable(subscriptionId));
        when(paymentGatewayClient.charge(any(), any())).thenReturn(new ChargeResult.FailedTransiently("gateway_timeout"));

        runner().run();

        verify(chargeRecordingPort, never()).recordSuccessfulCharge(any(), any(), any(), any(), any());
        verify(chargeRecordingPort, never()).recordFailedCharge(any(), any(), any(), any());
        verify(billingCycleAdvancePort, never()).advanceDueDate(any(), any());
        verify(dunningHandoff, never()).onChargeFailed(any(), any());
        assertThat(meterRegistry.get("billing_job_declined_charges_total").counter().count()).isEqualTo(0.0);
    }

    @Test
    void aDunningHandoffThatThrowsIsCountedAsAnUnexpectedFailureNotADeclinedCharge() {
        UUID subscriptionId = UUID.randomUUID();
        when(dueSubscriptionsPort.findDueSubscriptionIds(TODAY)).thenReturn(List.of(subscriptionId));
        when(chargeableSubscriptionPort.loadForCharge(subscriptionId)).thenReturn(chargeable(subscriptionId));
        when(paymentGatewayClient.charge(any(), any())).thenReturn(new ChargeResult.Declined("card_declined"));
        when(chargeRecordingPort.recordFailedCharge(any(), any(), any(), any())).thenReturn(UUID.randomUUID());
        org.mockito.Mockito.doThrow(new IllegalStateException("dunning unavailable"))
                .when(dunningHandoff).onChargeFailed(any(), any());

        runner().run();

        assertThat(meterRegistry.get("billing_job_declined_charges_total").counter().count()).isEqualTo(0.0);
        assertThat(meterRegistry.get("billing_job_charge_attempt_failures_total").counter().count()).isEqualTo(1.0);
    }

    @Test
    void chargesEachDueSubscriptionThroughTheGatewayWithItsOwnAmountAndPaymentMethodToken() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        ChargeableSubscription firstChargeable = new ChargeableSubscription(
                first, "tok_first", new BigDecimal("19.00"), UUID.randomUUID(), LocalDate.of(2026, 8, 24), 24);
        ChargeableSubscription secondChargeable = new ChargeableSubscription(
                second, "tok_second", new BigDecimal("49.00"), UUID.randomUUID(), LocalDate.of(2026, 8, 24), 24);
        when(dueSubscriptionsPort.findDueSubscriptionIds(TODAY)).thenReturn(List.of(first, second));
        when(chargeableSubscriptionPort.loadForCharge(first)).thenReturn(firstChargeable);
        when(chargeableSubscriptionPort.loadForCharge(second)).thenReturn(secondChargeable);
        when(paymentGatewayClient.charge(any(), any())).thenReturn(new ChargeResult.Succeeded("gw-txn"));

        runner().run();

        ArgumentCaptor<String> tokens = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<BigDecimal> amounts = ArgumentCaptor.forClass(BigDecimal.class);
        verify(paymentGatewayClient, org.mockito.Mockito.times(2)).charge(tokens.capture(), amounts.capture());
        assertThat(tokens.getAllValues()).containsExactlyInAnyOrder("tok_first", "tok_second");
        assertThat(amounts.getAllValues()).containsExactlyInAnyOrder(new BigDecimal("19.00"), new BigDecimal("49.00"));
    }

    @Test
    void oneSubscriptionsChargeAttemptThrowingDoesNotPreventTheRestFromBeingCharged() {
        UUID failing = UUID.randomUUID();
        UUID healthy = UUID.randomUUID();
        when(dueSubscriptionsPort.findDueSubscriptionIds(TODAY)).thenReturn(List.of(failing, healthy));
        when(chargeableSubscriptionPort.loadForCharge(failing)).thenThrow(new IllegalStateException("boom"));
        when(chargeableSubscriptionPort.loadForCharge(healthy)).thenReturn(chargeable(healthy));
        when(paymentGatewayClient.charge(any(), any())).thenReturn(new ChargeResult.Succeeded("gw-txn"));

        List<UUID> selected = runner().run();

        assertThat(selected).containsExactlyInAnyOrder(failing, healthy);
        verify(chargeRecordingPort).recordSuccessfulCharge(eq(healthy), any(), any(), any(), any());
        assertThat(meterRegistry.get("billing_job_subscriptions_processed_total").counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.get("billing_job_charge_attempt_failures_total").counter().count()).isEqualTo(1.0);
    }

    private ChargeableSubscription chargeable(UUID subscriptionId) {
        return new ChargeableSubscription(
                subscriptionId, "tok_visa", new BigDecimal("19.00"), UUID.randomUUID(), TODAY, 24);
    }
}
