package com.subscriptionbilling.billingjob.reconciliation;

import com.subscriptionbilling.billingjob.charge.ChargeableSubscription;
import com.subscriptionbilling.billingjob.charge.ChargeableSubscriptionPort;
import com.subscriptionbilling.billingjob.invoicing.ChargeAlreadyRecordedException;
import com.subscriptionbilling.billingjob.invoicing.ChargeOutcomeApplier;
import com.subscriptionbilling.billingjob.invoicing.ChargeRecordingPort;
import com.subscriptionbilling.billingjob.invoicing.RecordedChargeOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit coverage of {@link PaymentOutcomeReconciler}: a gateway reference not yet
 * recorded applies the reported outcome through {@link ChargeOutcomeApplier}; one
 * already recorded with the same outcome is a no-op; one already recorded with the
 * opposite outcome is reported as a conflict, never applied.
 */
@ExtendWith(MockitoExtension.class)
class PaymentOutcomeReconcilerTest {

    private static final Instant OCCURRED_AT = Instant.parse("2027-01-08T10:00:00Z");

    @Mock
    private ChargeRecordingPort chargeRecordingPort;
    @Mock
    private ChargeableSubscriptionPort chargeableSubscriptionPort;
    @Mock
    private ChargeOutcomeApplier chargeOutcomeApplier;

    private PaymentOutcomeReconciler reconciler() {
        return new PaymentOutcomeReconciler(chargeRecordingPort, chargeableSubscriptionPort, chargeOutcomeApplier);
    }

    private ChargeableSubscription chargeable(UUID subscriptionId, boolean dunningRetry) {
        return new ChargeableSubscription(subscriptionId, "tok_visa", new BigDecimal("19.00"),
                UUID.randomUUID(), LocalDate.of(2027, 1, 8), 8, dunningRetry);
    }

    @Test
    void aSucceededOutcomeNotYetRecordedIsAppliedThroughTheSharedApplier() {
        UUID subscriptionId = UUID.randomUUID();
        ChargeableSubscription chargeable = chargeable(subscriptionId, false);
        when(chargeRecordingPort.findRecordedOutcome("gw-1")).thenReturn(Optional.empty());
        when(chargeableSubscriptionPort.loadForCharge(subscriptionId)).thenReturn(chargeable);

        ReconciliationOutcome outcome = reconciler().reconcileSucceeded(subscriptionId, "gw-1", OCCURRED_AT);

        assertThat(outcome).isEqualTo(ReconciliationOutcome.APPLIED);
        verify(chargeOutcomeApplier).applySuccess(subscriptionId, chargeable, "gw-1", OCCURRED_AT, "gw-1");
    }

    @Test
    void aFailedOutcomeForANonRetrySubscriptionAppliesTheFirstFailurePath() {
        UUID subscriptionId = UUID.randomUUID();
        ChargeableSubscription chargeable = chargeable(subscriptionId, false);
        when(chargeRecordingPort.findRecordedOutcome("gw-2")).thenReturn(Optional.empty());
        when(chargeableSubscriptionPort.loadForCharge(subscriptionId)).thenReturn(chargeable);

        ReconciliationOutcome outcome = reconciler().reconcileFailed(subscriptionId, "gw-2", OCCURRED_AT);

        assertThat(outcome).isEqualTo(ReconciliationOutcome.APPLIED);
        verify(chargeOutcomeApplier).applyFirstFailure(subscriptionId, chargeable, "gw-2", OCCURRED_AT, "gw-2");
        verify(chargeOutcomeApplier, never()).applyRetryFailure(any(), any(), any(), any(), any());
    }

    @Test
    void aFailedOutcomeForASuspendedSubscriptionAppliesTheRetryFailurePath() {
        UUID subscriptionId = UUID.randomUUID();
        ChargeableSubscription chargeable = chargeable(subscriptionId, true);
        when(chargeRecordingPort.findRecordedOutcome("gw-3")).thenReturn(Optional.empty());
        when(chargeableSubscriptionPort.loadForCharge(subscriptionId)).thenReturn(chargeable);

        ReconciliationOutcome outcome = reconciler().reconcileFailed(subscriptionId, "gw-3", OCCURRED_AT);

        assertThat(outcome).isEqualTo(ReconciliationOutcome.APPLIED);
        verify(chargeOutcomeApplier).applyRetryFailure(subscriptionId, chargeable, "gw-3", OCCURRED_AT, "gw-3");
        verify(chargeOutcomeApplier, never()).applyFirstFailure(any(), any(), any(), any(), any());
    }

    @Test
    void aSucceededOutcomeAlreadyRecordedWithTheSameOutcomeIsANoOp() {
        UUID subscriptionId = UUID.randomUUID();
        when(chargeRecordingPort.findRecordedOutcome("gw-4")).thenReturn(Optional.of(RecordedChargeOutcome.SUCCEEDED));

        ReconciliationOutcome outcome = reconciler().reconcileSucceeded(subscriptionId, "gw-4", OCCURRED_AT);

        assertThat(outcome).isEqualTo(ReconciliationOutcome.ALREADY_RECORDED);
        verify(chargeableSubscriptionPort, never()).loadForCharge(any());
        verify(chargeOutcomeApplier, never()).applySuccess(any(), any(), any(), any(), any());
    }

    @Test
    void aFailedOutcomeAlreadyRecordedWithTheSameOutcomeIsANoOp() {
        UUID subscriptionId = UUID.randomUUID();
        when(chargeRecordingPort.findRecordedOutcome("gw-5")).thenReturn(Optional.of(RecordedChargeOutcome.FAILED));

        ReconciliationOutcome outcome = reconciler().reconcileFailed(subscriptionId, "gw-5", OCCURRED_AT);

        assertThat(outcome).isEqualTo(ReconciliationOutcome.ALREADY_RECORDED);
        verify(chargeableSubscriptionPort, never()).loadForCharge(any());
    }

    @Test
    void aSucceededReportForAGatewayReferenceAlreadyRecordedAsFailedIsAConflictNeverApplied() {
        UUID subscriptionId = UUID.randomUUID();
        when(chargeRecordingPort.findRecordedOutcome("gw-6")).thenReturn(Optional.of(RecordedChargeOutcome.FAILED));

        ReconciliationOutcome outcome = reconciler().reconcileSucceeded(subscriptionId, "gw-6", OCCURRED_AT);

        assertThat(outcome).isEqualTo(ReconciliationOutcome.CONFLICTING_OUTCOME);
        verify(chargeableSubscriptionPort, never()).loadForCharge(any());
        verify(chargeOutcomeApplier, never()).applySuccess(any(), any(), any(), any(), any());
    }

    @Test
    void aFailedReportForAGatewayReferenceAlreadyRecordedAsSucceededIsAConflictNeverApplied() {
        UUID subscriptionId = UUID.randomUUID();
        when(chargeRecordingPort.findRecordedOutcome("gw-7")).thenReturn(Optional.of(RecordedChargeOutcome.SUCCEEDED));

        ReconciliationOutcome outcome = reconciler().reconcileFailed(subscriptionId, "gw-7", OCCURRED_AT);

        assertThat(outcome).isEqualTo(ReconciliationOutcome.CONFLICTING_OUTCOME);
        verify(chargeableSubscriptionPort, never()).loadForCharge(any());
    }

    @Test
    void losingTheConcurrentRecordRaceWhileApplyingIsTreatedAsAlreadyRecordedNotAFailure() {
        UUID subscriptionId = UUID.randomUUID();
        ChargeableSubscription chargeable = chargeable(subscriptionId, false);
        when(chargeRecordingPort.findRecordedOutcome("gw-8")).thenReturn(Optional.empty());
        when(chargeableSubscriptionPort.loadForCharge(subscriptionId)).thenReturn(chargeable);
        doThrow(new ChargeAlreadyRecordedException("already recorded", new RuntimeException()))
                .when(chargeOutcomeApplier).applySuccess(any(), any(), any(), any(), any());

        ReconciliationOutcome outcome = reconciler().reconcileSucceeded(subscriptionId, "gw-8", OCCURRED_AT);

        assertThat(outcome).isEqualTo(ReconciliationOutcome.ALREADY_RECORDED);
    }
}
