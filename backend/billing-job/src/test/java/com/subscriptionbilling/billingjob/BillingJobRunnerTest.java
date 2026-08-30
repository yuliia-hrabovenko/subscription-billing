package com.subscriptionbilling.billingjob;

import com.subscriptionbilling.billingjob.anchor.BillingCycleAdvancePort;
import com.subscriptionbilling.billingjob.charge.ChargeableSubscription;
import com.subscriptionbilling.billingjob.charge.ChargeableSubscriptionPort;
import com.subscriptionbilling.billingjob.due.DueSubscriptionsPort;
import com.subscriptionbilling.billingjob.dunning.DunningHandoff;
import com.subscriptionbilling.billingjob.dunning.DunningRetryCharge;
import com.subscriptionbilling.billingjob.dunning.DunningRetryOutcome;
import com.subscriptionbilling.billingjob.gateway.ChargeResult;
import com.subscriptionbilling.billingjob.gateway.PaymentGatewayClient;
import com.subscriptionbilling.billingjob.invoicing.ChargeAlreadyRecordedException;
import com.subscriptionbilling.billingjob.invoicing.ChargeOutcomeApplier;
import com.subscriptionbilling.billingjob.invoicing.ChargeRecordingPort;
import com.subscriptionbilling.billingjob.invoicing.DunningRetryState;
import com.subscriptionbilling.billingjob.planchange.PendingPlanChangeOutcome;
import com.subscriptionbilling.billingjob.planchange.PendingPlanChangePort;
import com.subscriptionbilling.billingjob.trial.TrialEndingSoonPort;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit coverage of {@link BillingJobRunner}: it resolves today's date from its {@link
 * Clock}, drives one charge attempt per Subscription {@link DueSubscriptionsPort}
 * selects for it, and records/advances only on a successful charge; separately, it
 * drives one notification attempt per Subscription {@link TrialEndingSoonPort} selects
 * as approaching its Trial end. Whether the due-date/trial-end comparisons, charge-detail
 * loading, or the recording/advancing/notifying ports' persistence is correct against
 * real data is each port implementation's own contract, covered where that
 * implementation lives.
 */
@ExtendWith(MockitoExtension.class)
class BillingJobRunnerTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 24);
    private static final Clock FIXED_CLOCK = Clock.fixed(TODAY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);

    @Mock
    private DueSubscriptionsPort dueSubscriptionsPort;

    @Mock
    private PendingPlanChangePort pendingPlanChangePort;

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

    @Mock
    private TrialEndingSoonPort trialEndingSoonPort;

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    @BeforeEach
    void noTrialsEndingSoonByDefault() {
        // Every run() call reaches the trial-ending-soon step regardless of what a given
        // test is exercising -- an unstubbed mock would return null here and NPE the
        // for-each. A test proving that step's own behavior overrides this.
        lenient().when(trialEndingSoonPort.findTrialsEndingSoon(any())).thenReturn(List.of());
    }

    private BillingJobRunner runner() {
        // A plain instance wrapping this test's own mocks, not a separately mocked
        // collaborator -- every verify() below against chargeRecordingPort/
        // billingCycleAdvancePort/dunningHandoff still observes calls ChargeOutcomeApplier
        // makes through them, whether from BillingJobRunner's own success/decline path or
        // from DunningRetryCharge's retry path.
        ChargeOutcomeApplier chargeOutcomeApplier =
                new ChargeOutcomeApplier(chargeRecordingPort, billingCycleAdvancePort, dunningHandoff);
        DunningRetryCharge dunningRetryCharge = new DunningRetryCharge(paymentGatewayClient, chargeOutcomeApplier);
        return new BillingJobRunner(dueSubscriptionsPort, pendingPlanChangePort, chargeableSubscriptionPort,
                paymentGatewayClient, chargeRecordingPort, dunningRetryCharge, chargeOutcomeApplier,
                trialEndingSoonPort, meterRegistry, FIXED_CLOCK);
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
        verify(billingCycleAdvancePort).advanceDueDate(eq(subscriptionId), eq(LocalDate.of(2026, 2, 28)), any());
    }

    @Test
    void aSuccessfulChargeCatchingUpAMissedRunAdvancesFromTheOriginalDueDateNotFromToday() {
        UUID subscriptionId = UUID.randomUUID();
        // Anchored to the 21st; TODAY (Aug 24) simulates the run catching up 3 days late.
        // The next due date must land on Sep 21 -- derived from the missed due date, not
        // from TODAY, which would otherwise drift the Customer's billing day.
        ChargeableSubscription chargeable = new ChargeableSubscription(
                subscriptionId, "tok_visa", new BigDecimal("19.00"), UUID.randomUUID(),
                LocalDate.of(2026, 8, 21), 21);
        when(dueSubscriptionsPort.findDueSubscriptionIds(TODAY)).thenReturn(List.of(subscriptionId));
        when(chargeableSubscriptionPort.loadForCharge(subscriptionId)).thenReturn(chargeable);
        when(paymentGatewayClient.charge("tok_visa", new BigDecimal("19.00")))
                .thenReturn(new ChargeResult.Succeeded("gw-txn-1"));

        runner().run();

        verify(billingCycleAdvancePort).advanceDueDate(eq(subscriptionId), eq(LocalDate.of(2026, 9, 21)), any());
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
                chargeable.priceVersionId(), null, FIXED_CLOCK.instant())).thenReturn(invoiceId);

        runner().run();

        verify(chargeRecordingPort).recordFailedCharge(subscriptionId, chargeable.billingPeriod(),
                chargeable.priceVersionId(), null, FIXED_CLOCK.instant());
        verify(dunningHandoff).onChargeFailed(
                eq(subscriptionId), eq(invoiceId), eq(chargeable.billingPeriod()), eq(FIXED_CLOCK.instant()), any());
        verify(chargeRecordingPort, never()).recordSuccessfulCharge(any(), any(), any(), any(), any());
        verify(billingCycleAdvancePort, never()).advanceDueDate(any(), any(), any());
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
        verify(chargeRecordingPort, never()).recordFailedCharge(any(), any(), any(), any(), any());
        verify(billingCycleAdvancePort, never()).advanceDueDate(any(), any(), any());
        verify(dunningHandoff, never()).onChargeFailed(any(), any(), any(), any(), any());
        assertThat(meterRegistry.get("billing_job_declined_charges_total").counter().count()).isEqualTo(0.0);
    }

    @Test
    void aDunningHandoffThatThrowsIsCountedAsAnUnexpectedFailureNotADeclinedCharge() {
        UUID subscriptionId = UUID.randomUUID();
        when(dueSubscriptionsPort.findDueSubscriptionIds(TODAY)).thenReturn(List.of(subscriptionId));
        when(chargeableSubscriptionPort.loadForCharge(subscriptionId)).thenReturn(chargeable(subscriptionId));
        when(paymentGatewayClient.charge(any(), any())).thenReturn(new ChargeResult.Declined("card_declined"));
        when(chargeRecordingPort.recordFailedCharge(any(), any(), any(), any(), any())).thenReturn(UUID.randomUUID());
        org.mockito.Mockito.doThrow(new IllegalStateException("dunning unavailable"))
                .when(dunningHandoff).onChargeFailed(any(), any(), any(), any(), any());

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

    @Test
    void aSubscriptionAlreadyInvoicedForItsBillingPeriodIsSkippedWithoutCallingTheGateway() {
        UUID subscriptionId = UUID.randomUUID();
        ChargeableSubscription chargeable = chargeable(subscriptionId);
        when(dueSubscriptionsPort.findDueSubscriptionIds(TODAY)).thenReturn(List.of(subscriptionId));
        when(chargeableSubscriptionPort.loadForCharge(subscriptionId)).thenReturn(chargeable);
        when(chargeRecordingPort.invoiceAlreadyRecorded(subscriptionId, chargeable.billingPeriod())).thenReturn(true);

        runner().run();

        verify(paymentGatewayClient, never()).charge(any(), any());
        verify(billingCycleAdvancePort, never()).advanceDueDate(any(), any(), any());
        verify(dunningHandoff, never()).onChargeFailed(any(), any(), any(), any(), any());
        assertThat(meterRegistry.get("billing_job_duplicate_charge_skipped_total").counter().count()).isEqualTo(1.0);
    }

    @Test
    void invokingRunTwiceInARowForTheSameSubscriptionChargesTheGatewayAtMostOnce() {
        UUID subscriptionId = UUID.randomUUID();
        ChargeableSubscription chargeable = chargeable(subscriptionId);
        when(dueSubscriptionsPort.findDueSubscriptionIds(TODAY)).thenReturn(List.of(subscriptionId));
        when(chargeableSubscriptionPort.loadForCharge(subscriptionId)).thenReturn(chargeable);
        // Not yet recorded on the first run; recorded (by this very run) by the second.
        when(chargeRecordingPort.invoiceAlreadyRecorded(subscriptionId, chargeable.billingPeriod()))
                .thenReturn(false, true);
        when(paymentGatewayClient.charge(any(), any())).thenReturn(new ChargeResult.Succeeded("gw-txn-1"));

        BillingJobRunner runner = runner();
        runner.run();
        runner.run();

        verify(paymentGatewayClient, org.mockito.Mockito.times(1)).charge(any(), any());
        verify(chargeRecordingPort, org.mockito.Mockito.times(1))
                .recordSuccessfulCharge(any(), any(), any(), any(), any());
        assertThat(meterRegistry.get("billing_job_duplicate_charge_skipped_total").counter().count()).isEqualTo(1.0);
    }

    @Test
    void losingTheConcurrentCreateInvoiceRaceOnASuccessfulChargeIsCountedAsADuplicateSkipNotAFailure() {
        UUID subscriptionId = UUID.randomUUID();
        when(dueSubscriptionsPort.findDueSubscriptionIds(TODAY)).thenReturn(List.of(subscriptionId));
        when(chargeableSubscriptionPort.loadForCharge(subscriptionId)).thenReturn(chargeable(subscriptionId));
        when(paymentGatewayClient.charge(any(), any())).thenReturn(new ChargeResult.Succeeded("gw-txn-1"));
        org.mockito.Mockito.doThrow(new ChargeAlreadyRecordedException("already recorded", new RuntimeException()))
                .when(chargeRecordingPort).recordSuccessfulCharge(any(), any(), any(), any(), any());

        runner().run();

        verify(billingCycleAdvancePort, never()).advanceDueDate(any(), any(), any());
        assertThat(meterRegistry.get("billing_job_subscriptions_processed_total").counter().count()).isEqualTo(0.0);
        assertThat(meterRegistry.get("billing_job_duplicate_charge_skipped_total").counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.get("billing_job_charge_attempt_failures_total").counter().count()).isEqualTo(0.0);
    }

    @Test
    void losingTheConcurrentCreateInvoiceRaceOnADeclineIsCountedAsADuplicateSkipNotAFailure() {
        UUID subscriptionId = UUID.randomUUID();
        when(dueSubscriptionsPort.findDueSubscriptionIds(TODAY)).thenReturn(List.of(subscriptionId));
        when(chargeableSubscriptionPort.loadForCharge(subscriptionId)).thenReturn(chargeable(subscriptionId));
        when(paymentGatewayClient.charge(any(), any())).thenReturn(new ChargeResult.Declined("card_declined"));
        org.mockito.Mockito.doThrow(new ChargeAlreadyRecordedException("already recorded", new RuntimeException()))
                .when(chargeRecordingPort).recordFailedCharge(any(), any(), any(), any(), any());

        runner().run();

        verify(dunningHandoff, never()).onChargeFailed(any(), any(), any(), any(), any());
        assertThat(meterRegistry.get("billing_job_declined_charges_total").counter().count()).isEqualTo(0.0);
        assertThat(meterRegistry.get("billing_job_duplicate_charge_skipped_total").counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.get("billing_job_charge_attempt_failures_total").counter().count()).isEqualTo(0.0);
    }

    @Test
    void aPendingPlanChangeAppliedToAFreePlanSkipsTheChargeAttemptEntirely() {
        UUID subscriptionId = UUID.randomUUID();
        when(dueSubscriptionsPort.findDueSubscriptionIds(TODAY)).thenReturn(List.of(subscriptionId));
        when(pendingPlanChangePort.applyIfPending(eq(subscriptionId), any(String.class)))
                .thenReturn(PendingPlanChangeOutcome.APPLIED_FREE);

        runner().run();

        verify(chargeableSubscriptionPort, never()).loadForCharge(any());
        verify(paymentGatewayClient, never()).charge(any(), any());
        verify(chargeRecordingPort, never()).recordSuccessfulCharge(any(), any(), any(), any(), any());
        verify(chargeRecordingPort, never()).recordFailedCharge(any(), any(), any(), any(), any());
        assertThat(meterRegistry.get("billing_job_subscriptions_processed_total").counter().count()).isEqualTo(0.0);
    }

    @Test
    void aPendingPlanChangeAppliedToAStillPaidPlanProceedsToChargeThisCycle() {
        UUID subscriptionId = UUID.randomUUID();
        when(dueSubscriptionsPort.findDueSubscriptionIds(TODAY)).thenReturn(List.of(subscriptionId));
        when(pendingPlanChangePort.applyIfPending(eq(subscriptionId), any(String.class)))
                .thenReturn(PendingPlanChangeOutcome.APPLIED_PAID);
        when(chargeableSubscriptionPort.loadForCharge(subscriptionId)).thenReturn(chargeable(subscriptionId));
        when(paymentGatewayClient.charge(any(), any())).thenReturn(new ChargeResult.Succeeded("gw-txn-1"));

        runner().run();

        verify(chargeRecordingPort).recordSuccessfulCharge(eq(subscriptionId), any(), any(), any(), any());
        assertThat(meterRegistry.get("billing_job_subscriptions_processed_total").counter().count()).isEqualTo(1.0);
    }

    @Test
    void noPendingPlanChangeProceedsToChargeAsNormal() {
        UUID subscriptionId = UUID.randomUUID();
        when(dueSubscriptionsPort.findDueSubscriptionIds(TODAY)).thenReturn(List.of(subscriptionId));
        when(pendingPlanChangePort.applyIfPending(eq(subscriptionId), any(String.class)))
                .thenReturn(PendingPlanChangeOutcome.NO_PENDING_CHANGE);
        when(chargeableSubscriptionPort.loadForCharge(subscriptionId)).thenReturn(chargeable(subscriptionId));
        when(paymentGatewayClient.charge(any(), any())).thenReturn(new ChargeResult.Succeeded("gw-txn-1"));

        runner().run();

        verify(chargeRecordingPort).recordSuccessfulCharge(eq(subscriptionId), any(), any(), any(), any());
    }

    @Test
    void aSuccessfulDunningRetryRecordsItAdvancesTheBillingCycleAndIncrementsAttemptAndRecoveryCountersNotSubscriptionsProcessed() {
        UUID subscriptionId = UUID.randomUUID();
        ChargeableSubscription retryChargeable = dunningRetryChargeable(subscriptionId);
        when(dueSubscriptionsPort.findDueSubscriptionIds(TODAY)).thenReturn(List.of(subscriptionId));
        when(chargeableSubscriptionPort.loadForCharge(subscriptionId)).thenReturn(retryChargeable);
        when(paymentGatewayClient.charge(any(), any())).thenReturn(new ChargeResult.Succeeded("gw-txn-1"));

        runner().run();

        verify(chargeRecordingPort).recordSuccessfulCharge(eq(subscriptionId), eq(retryChargeable.billingPeriod()),
                any(), eq("gw-txn-1"), any());
        verify(billingCycleAdvancePort).advanceDueDate(eq(subscriptionId), any(), any());
        assertThat(meterRegistry.get("billing_job_dunning_attempts_total").counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.get("billing_job_dunning_recoveries_total").counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.get("billing_job_subscriptions_processed_total").counter().count()).isEqualTo(0.0);
    }

    @Test
    void aFailedDunningRetryRecordsAgainstTheSameInvoiceAndReschedulesWhenNotExhausted() {
        UUID subscriptionId = UUID.randomUUID();
        UUID invoiceId = UUID.randomUUID();
        ChargeableSubscription retryChargeable = dunningRetryChargeable(subscriptionId);
        DunningRetryState retryState = new DunningRetryState(Instant.parse("2027-01-01T10:00:00Z"), 1, false);
        when(dueSubscriptionsPort.findDueSubscriptionIds(TODAY)).thenReturn(List.of(subscriptionId));
        when(chargeableSubscriptionPort.loadForCharge(subscriptionId)).thenReturn(retryChargeable);
        when(paymentGatewayClient.charge(any(), any())).thenReturn(new ChargeResult.Declined("card_declined"));
        when(chargeRecordingPort.recordFailedCharge(subscriptionId, retryChargeable.billingPeriod(),
                retryChargeable.priceVersionId(), null, FIXED_CLOCK.instant())).thenReturn(invoiceId);
        when(chargeRecordingPort.recordRetryAttempt(invoiceId)).thenReturn(retryState);
        when(dunningHandoff.onRetryFailed(eq(subscriptionId), eq(invoiceId), eq(retryState), eq(FIXED_CLOCK.instant()), any()))
                .thenReturn(DunningRetryOutcome.RESCHEDULED);

        runner().run();

        verify(chargeRecordingPort).recordRetryAttempt(invoiceId);
        verify(dunningHandoff).onRetryFailed(eq(subscriptionId), eq(invoiceId), eq(retryState), eq(FIXED_CLOCK.instant()), any());
        verify(dunningHandoff, never()).onChargeFailed(any(), any(), any(), any(), any());
        assertThat(meterRegistry.get("billing_job_dunning_attempts_total").counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.get("billing_job_dunning_exhaustions_total").counter().count()).isEqualTo(0.0);
        assertThat(meterRegistry.get("billing_job_declined_charges_total").counter().count()).isEqualTo(0.0);
    }

    @Test
    void aFailedDunningRetryThatExhaustsTheBoundIncrementsTheExhaustionCounter() {
        UUID subscriptionId = UUID.randomUUID();
        UUID invoiceId = UUID.randomUUID();
        ChargeableSubscription retryChargeable = dunningRetryChargeable(subscriptionId);
        DunningRetryState retryState = new DunningRetryState(Instant.parse("2027-01-01T10:00:00Z"), 3, true);
        when(dueSubscriptionsPort.findDueSubscriptionIds(TODAY)).thenReturn(List.of(subscriptionId));
        when(chargeableSubscriptionPort.loadForCharge(subscriptionId)).thenReturn(retryChargeable);
        when(paymentGatewayClient.charge(any(), any())).thenReturn(new ChargeResult.Declined("card_declined"));
        when(chargeRecordingPort.recordFailedCharge(subscriptionId, retryChargeable.billingPeriod(),
                retryChargeable.priceVersionId(), null, FIXED_CLOCK.instant())).thenReturn(invoiceId);
        when(chargeRecordingPort.recordRetryAttempt(invoiceId)).thenReturn(retryState);
        when(dunningHandoff.onRetryFailed(eq(subscriptionId), eq(invoiceId), eq(retryState), eq(FIXED_CLOCK.instant()), any()))
                .thenReturn(DunningRetryOutcome.CANCELED);

        runner().run();

        assertThat(meterRegistry.get("billing_job_dunning_attempts_total").counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.get("billing_job_dunning_exhaustions_total").counter().count()).isEqualTo(1.0);
    }

    @Test
    void aDunningRetryIsNeverSkippedAsAlreadyInvoicedEvenThoughItsInvoiceAlreadyExists() {
        // Unlike a fresh renewal, a due Dunning retry's Invoice always already exists
        // (created by the initial failure) -- the duplicate-run pre-check must not
        // mistake that for "already recorded, skip" or no retry would ever charge.
        UUID subscriptionId = UUID.randomUUID();
        ChargeableSubscription retryChargeable = dunningRetryChargeable(subscriptionId);
        when(dueSubscriptionsPort.findDueSubscriptionIds(TODAY)).thenReturn(List.of(subscriptionId));
        when(chargeableSubscriptionPort.loadForCharge(subscriptionId)).thenReturn(retryChargeable);
        when(paymentGatewayClient.charge(any(), any())).thenReturn(new ChargeResult.Succeeded("gw-txn-1"));

        runner().run();

        verify(chargeRecordingPort, never()).invoiceAlreadyRecorded(any(), any());
        verify(paymentGatewayClient).charge(any(), any());
    }

    @Test
    void aSuspensionAndARecoveryProcessedInTheSameRunShareTheSameCorrelationId() {
        UUID suspending = UUID.randomUUID();
        UUID recovering = UUID.randomUUID();
        // Distinct payment method tokens -- not just distinct Subscription ids -- so each
        // Subscription's gateway.charge() stub only matches its own invocation.
        ChargeableSubscription suspendingChargeable = new ChargeableSubscription(
                suspending, "tok_suspend", new BigDecimal("19.00"), UUID.randomUUID(), TODAY, 24);
        ChargeableSubscription recoveringChargeable = new ChargeableSubscription(
                recovering, "tok_recover", new BigDecimal("19.00"), UUID.randomUUID(), TODAY, 24, true);
        when(dueSubscriptionsPort.findDueSubscriptionIds(TODAY)).thenReturn(List.of(suspending, recovering));
        when(chargeableSubscriptionPort.loadForCharge(suspending)).thenReturn(suspendingChargeable);
        when(chargeableSubscriptionPort.loadForCharge(recovering)).thenReturn(recoveringChargeable);
        when(paymentGatewayClient.charge(suspendingChargeable.paymentMethodToken(), suspendingChargeable.amount()))
                .thenReturn(new ChargeResult.Declined("card_declined"));
        when(paymentGatewayClient.charge(recoveringChargeable.paymentMethodToken(), recoveringChargeable.amount()))
                .thenReturn(new ChargeResult.Succeeded("gw-txn-1"));

        runner().run();

        ArgumentCaptor<String> suspensionCorrelationId = ArgumentCaptor.forClass(String.class);
        verify(dunningHandoff).onChargeFailed(eq(suspending), any(), any(), any(), suspensionCorrelationId.capture());
        ArgumentCaptor<String> recoveryCorrelationId = ArgumentCaptor.forClass(String.class);
        verify(billingCycleAdvancePort).advanceDueDate(eq(recovering), any(), recoveryCorrelationId.capture());

        assertThat(suspensionCorrelationId.getValue()).isEqualTo(recoveryCorrelationId.getValue());
    }

    @Test
    void subscriptionsProcessedInDifferentRunsGetDifferentCorrelationIds() {
        UUID firstRunSubscription = UUID.randomUUID();
        UUID secondRunSubscription = UUID.randomUUID();
        when(chargeableSubscriptionPort.loadForCharge(firstRunSubscription)).thenReturn(chargeable(firstRunSubscription));
        when(chargeableSubscriptionPort.loadForCharge(secondRunSubscription)).thenReturn(chargeable(secondRunSubscription));
        when(paymentGatewayClient.charge(any(), any())).thenReturn(new ChargeResult.Declined("card_declined"));

        BillingJobRunner runner = runner();
        when(dueSubscriptionsPort.findDueSubscriptionIds(TODAY)).thenReturn(List.of(firstRunSubscription));
        runner.run();
        when(dueSubscriptionsPort.findDueSubscriptionIds(TODAY)).thenReturn(List.of(secondRunSubscription));
        runner.run();

        ArgumentCaptor<String> correlationIds = ArgumentCaptor.forClass(String.class);
        verify(dunningHandoff, org.mockito.Mockito.times(2))
                .onChargeFailed(any(), any(), any(), any(), correlationIds.capture());

        assertThat(correlationIds.getAllValues().get(0)).isNotEqualTo(correlationIds.getAllValues().get(1));
    }

    @Test
    void runScansForTrialsEndingSoonUsingACutoffThreeDaysAheadOfNow() {
        when(dueSubscriptionsPort.findDueSubscriptionIds(TODAY)).thenReturn(List.of());

        runner().run();

        verify(trialEndingSoonPort).findTrialsEndingSoon(FIXED_CLOCK.instant().plus(java.time.Duration.ofDays(3)));
    }

    @Test
    void aTrialEndingSoonCandidateIsNotifiedAndCountsAsSent() {
        UUID subscriptionId = UUID.randomUUID();
        when(dueSubscriptionsPort.findDueSubscriptionIds(TODAY)).thenReturn(List.of());
        when(trialEndingSoonPort.findTrialsEndingSoon(any())).thenReturn(List.of(subscriptionId));

        runner().run();

        verify(trialEndingSoonPort).notifyTrialEndingSoon(subscriptionId, FIXED_CLOCK.instant());
        assertThat(meterRegistry.get("billing_job_trial_ending_soon_notifications_sent_total").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void everyTrialEndingSoonCandidateIsNotified() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(dueSubscriptionsPort.findDueSubscriptionIds(TODAY)).thenReturn(List.of());
        when(trialEndingSoonPort.findTrialsEndingSoon(any())).thenReturn(List.of(first, second));

        runner().run();

        verify(trialEndingSoonPort).notifyTrialEndingSoon(first, FIXED_CLOCK.instant());
        verify(trialEndingSoonPort).notifyTrialEndingSoon(second, FIXED_CLOCK.instant());
    }

    @Test
    void oneTrialEndingSoonNotificationThrowingDoesNotPreventTheOthersOrTheDueSubscriptionIdsFromBeingReturned() {
        UUID dueSubscriptionId = UUID.randomUUID();
        UUID failingCandidate = UUID.randomUUID();
        UUID healthyCandidate = UUID.randomUUID();
        when(dueSubscriptionsPort.findDueSubscriptionIds(TODAY)).thenReturn(List.of(dueSubscriptionId));
        when(chargeableSubscriptionPort.loadForCharge(dueSubscriptionId)).thenReturn(chargeable(dueSubscriptionId));
        when(paymentGatewayClient.charge(any(), any())).thenReturn(new ChargeResult.Declined("card_declined"));
        when(trialEndingSoonPort.findTrialsEndingSoon(any())).thenReturn(List.of(failingCandidate, healthyCandidate));
        org.mockito.Mockito.doThrow(new IllegalStateException("boom"))
                .when(trialEndingSoonPort).notifyTrialEndingSoon(eq(failingCandidate), any());

        List<UUID> selected = runner().run();

        assertThat(selected).containsExactly(dueSubscriptionId);
        verify(trialEndingSoonPort).notifyTrialEndingSoon(healthyCandidate, FIXED_CLOCK.instant());
        assertThat(meterRegistry.get("billing_job_trial_ending_soon_notifications_sent_total").counter().count())
                .isEqualTo(1.0);
        assertThat(meterRegistry.get("billing_job_trial_ending_soon_notification_failures_total").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void noTrialEndingSoonCandidatesNotifiesNoOne() {
        when(dueSubscriptionsPort.findDueSubscriptionIds(TODAY)).thenReturn(List.of());

        runner().run();

        verify(trialEndingSoonPort, never()).notifyTrialEndingSoon(any(), any());
    }

    private ChargeableSubscription chargeable(UUID subscriptionId) {
        return new ChargeableSubscription(
                subscriptionId, "tok_visa", new BigDecimal("19.00"), UUID.randomUUID(), TODAY, 24);
    }

    private ChargeableSubscription dunningRetryChargeable(UUID subscriptionId) {
        return new ChargeableSubscription(
                subscriptionId, "tok_visa", new BigDecimal("19.00"), UUID.randomUUID(), TODAY, 24, true);
    }
}
