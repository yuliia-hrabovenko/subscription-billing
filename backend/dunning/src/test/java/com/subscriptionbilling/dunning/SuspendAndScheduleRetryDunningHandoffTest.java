package com.subscriptionbilling.dunning;

import com.subscriptionbilling.billingjob.dunning.DunningRetryOutcome;
import com.subscriptionbilling.billingjob.invoicing.DunningRetryState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Unit coverage of {@link SuspendAndScheduleRetryDunningHandoff}: {@link
 * SuspendAndScheduleRetryDunningHandoff#onChargeFailed} forwards a failed charge to
 * {@link SubscriptionSuspensionPort#suspend} with the failed charge's Subscription,
 * Billing Cycle date, and the triggering caller's correlation id, then schedules
 * the day-1 Dunning retry via {@link SubscriptionSuspensionPort#scheduleRetry} using
 * {@link DunningSchedule}'s day-1 offset computed from the failure instant. {@link
 * SuspendAndScheduleRetryDunningHandoff#onRetryFailed} either reschedules the next
 * offset (day 3 or day 7, computed from the Invoice's original failure instant, not
 * from this attempt's) or cancels the Subscription once the retry bound is reached.
 * Whether the suspension, retry scheduling, or cancellation itself succeeds against a
 * given Subscription state is the port implementation's own contract, covered where
 * that implementation lives.
 */
@ExtendWith(MockitoExtension.class)
class SuspendAndScheduleRetryDunningHandoffTest {

    private static final LocalDate BILLING_PERIOD = LocalDate.of(2027, 1, 1);

    @Mock
    private SubscriptionSuspensionPort subscriptionSuspensionPort;

    @Test
    void onChargeFailedSuspendsTheSubscriptionThroughThePortUsingTheGivenCorrelationId() {
        UUID subscriptionId = UUID.randomUUID();
        UUID invoiceId = UUID.randomUUID();
        Instant failedAt = Instant.parse("2027-01-01T10:00:00Z");

        new SuspendAndScheduleRetryDunningHandoff(subscriptionSuspensionPort)
                .onChargeFailed(subscriptionId, invoiceId, BILLING_PERIOD, failedAt, "corr-1");

        verify(subscriptionSuspensionPort).suspend(subscriptionId, BILLING_PERIOD, "corr-1");
    }

    @Test
    void onChargeFailedSchedulesTheDayOneRetryComputedFromTheFailureInstant() {
        UUID subscriptionId = UUID.randomUUID();
        UUID invoiceId = UUID.randomUUID();
        Instant failedAt = Instant.parse("2027-01-01T10:00:00Z");

        new SuspendAndScheduleRetryDunningHandoff(subscriptionSuspensionPort)
                .onChargeFailed(subscriptionId, invoiceId, BILLING_PERIOD, failedAt, "corr-1");

        verify(subscriptionSuspensionPort).scheduleRetry(subscriptionId, LocalDate.of(2027, 1, 2));
    }

    @Test
    void onChargeFailedResolvesTheDayOneRetryDateInUtcRegardlessOfHowCloseTheFailureIsToUtcMidnight() {
        UUID subscriptionId = UUID.randomUUID();
        UUID invoiceId = UUID.randomUUID();
        Instant failedAt = Instant.parse("2027-03-15T23:30:00Z");

        new SuspendAndScheduleRetryDunningHandoff(subscriptionSuspensionPort)
                .onChargeFailed(subscriptionId, invoiceId, BILLING_PERIOD, failedAt, "corr-1");

        verify(subscriptionSuspensionPort).scheduleRetry(subscriptionId, LocalDate.of(2027, 3, 16));
    }

    @Test
    void onRetryFailedAfterTheDayOneRetryReschedulesToDayThreeComputedFromTheOriginalFailureInstant() {
        UUID subscriptionId = UUID.randomUUID();
        UUID invoiceId = UUID.randomUUID();
        Instant originalFailureAt = Instant.parse("2027-01-01T10:00:00Z");
        DunningRetryState retryState = new DunningRetryState(originalFailureAt, 1, false);

        DunningRetryOutcome outcome = new SuspendAndScheduleRetryDunningHandoff(subscriptionSuspensionPort)
                .onRetryFailed(subscriptionId, invoiceId, retryState, Instant.parse("2027-01-02T10:00:00Z"), "corr-2");

        assertThat(outcome).isEqualTo(DunningRetryOutcome.RESCHEDULED);
        verify(subscriptionSuspensionPort).scheduleRetry(subscriptionId, LocalDate.of(2027, 1, 4));
        verify(subscriptionSuspensionPort, never()).cancel(any(), any());
    }

    @Test
    void onRetryFailedAfterTheDayThreeRetryReschedulesToDaySeven() {
        UUID subscriptionId = UUID.randomUUID();
        UUID invoiceId = UUID.randomUUID();
        Instant originalFailureAt = Instant.parse("2027-01-01T10:00:00Z");
        DunningRetryState retryState = new DunningRetryState(originalFailureAt, 2, false);

        DunningRetryOutcome outcome = new SuspendAndScheduleRetryDunningHandoff(subscriptionSuspensionPort)
                .onRetryFailed(subscriptionId, invoiceId, retryState, Instant.parse("2027-01-04T10:00:00Z"), "corr-2");

        assertThat(outcome).isEqualTo(DunningRetryOutcome.RESCHEDULED);
        verify(subscriptionSuspensionPort).scheduleRetry(subscriptionId, LocalDate.of(2027, 1, 8));
    }

    @Test
    void onRetryFailedWithTheRetryBoundReachedCancelsTheSubscriptionUsingTheGivenCorrelationId() {
        UUID subscriptionId = UUID.randomUUID();
        UUID invoiceId = UUID.randomUUID();
        DunningRetryState retryState = new DunningRetryState(Instant.parse("2027-01-01T10:00:00Z"), 3, true);

        DunningRetryOutcome outcome = new SuspendAndScheduleRetryDunningHandoff(subscriptionSuspensionPort)
                .onRetryFailed(subscriptionId, invoiceId, retryState, Instant.parse("2027-01-08T10:00:00Z"), "corr-3");

        assertThat(outcome).isEqualTo(DunningRetryOutcome.CANCELED);
        verify(subscriptionSuspensionPort).cancel(subscriptionId, "corr-3");
        verify(subscriptionSuspensionPort, never()).scheduleRetry(any(), any());
    }
}
