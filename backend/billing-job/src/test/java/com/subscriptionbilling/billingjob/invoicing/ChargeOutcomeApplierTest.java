package com.subscriptionbilling.billingjob.invoicing;

import com.subscriptionbilling.billingjob.anchor.BillingCycleAdvancePort;
import com.subscriptionbilling.billingjob.charge.ChargeableSubscription;
import com.subscriptionbilling.billingjob.dunning.DunningHandoff;
import com.subscriptionbilling.billingjob.dunning.DunningRetryOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit coverage of {@link ChargeOutcomeApplier}: it's the single place a resolved charge
 * outcome turns into Invoice/PaymentAttempt persistence and Subscription state,
 * regardless of which trigger resolved it.
 */
@ExtendWith(MockitoExtension.class)
class ChargeOutcomeApplierTest {

    private static final Instant ATTEMPTED_AT = Instant.parse("2027-01-08T10:00:00Z");

    @Mock
    private ChargeRecordingPort chargeRecordingPort;
    @Mock
    private BillingCycleAdvancePort billingCycleAdvancePort;
    @Mock
    private DunningHandoff dunningHandoff;

    private ChargeOutcomeApplier applier() {
        return new ChargeOutcomeApplier(chargeRecordingPort, billingCycleAdvancePort, dunningHandoff);
    }

    private ChargeableSubscription chargeable(UUID subscriptionId) {
        return new ChargeableSubscription(subscriptionId, "tok_visa", new BigDecimal("19.00"),
                UUID.randomUUID(), LocalDate.of(2027, 1, 8), 8);
    }

    @Test
    void applySuccessRecordsTheChargeAndAdvancesDueDateUsingAnchorDateClamping() {
        UUID subscriptionId = UUID.randomUUID();
        ChargeableSubscription chargeable = chargeable(subscriptionId);

        applier().applySuccess(subscriptionId, chargeable, "gw-txn-1", ATTEMPTED_AT);

        verify(chargeRecordingPort).recordSuccessfulCharge(subscriptionId, chargeable.billingPeriod(),
                chargeable.priceVersionId(), "gw-txn-1", ATTEMPTED_AT);
        verify(billingCycleAdvancePort).advanceDueDate(subscriptionId, LocalDate.of(2027, 2, 8), "gw-txn-1");
    }

    @Test
    void applyFirstFailureRecordsTheFailedAttemptAndHandsOffToChargeFailed() {
        UUID subscriptionId = UUID.randomUUID();
        UUID invoiceId = UUID.randomUUID();
        ChargeableSubscription chargeable = chargeable(subscriptionId);
        when(chargeRecordingPort.recordFailedCharge(subscriptionId, chargeable.billingPeriod(),
                chargeable.priceVersionId(), "gw-declined-1", ATTEMPTED_AT)).thenReturn(invoiceId);

        applier().applyFirstFailure(subscriptionId, chargeable, "gw-declined-1", ATTEMPTED_AT);

        verify(dunningHandoff).onChargeFailed(subscriptionId, invoiceId, chargeable.billingPeriod(), ATTEMPTED_AT);
    }

    @Test
    void applyRetryFailureRecordsTheAttemptIncrementsTheCounterAndDefersToOnRetryFailed() {
        UUID subscriptionId = UUID.randomUUID();
        UUID invoiceId = UUID.randomUUID();
        ChargeableSubscription chargeable = chargeable(subscriptionId);
        DunningRetryState retryState = new DunningRetryState(Instant.parse("2027-01-01T10:00:00Z"), 1, false);
        when(chargeRecordingPort.recordFailedCharge(subscriptionId, chargeable.billingPeriod(),
                chargeable.priceVersionId(), "gw-declined-2", ATTEMPTED_AT)).thenReturn(invoiceId);
        when(chargeRecordingPort.recordRetryAttempt(invoiceId)).thenReturn(retryState);
        when(dunningHandoff.onRetryFailed(subscriptionId, invoiceId, retryState, ATTEMPTED_AT))
                .thenReturn(DunningRetryOutcome.RESCHEDULED);

        DunningRetryOutcome outcome = applier().applyRetryFailure(subscriptionId, chargeable, "gw-declined-2", ATTEMPTED_AT);

        assertThat(outcome).isEqualTo(DunningRetryOutcome.RESCHEDULED);
        verify(chargeRecordingPort).recordRetryAttempt(invoiceId);
    }
}
