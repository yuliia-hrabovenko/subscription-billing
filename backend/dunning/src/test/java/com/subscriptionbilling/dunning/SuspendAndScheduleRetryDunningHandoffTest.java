package com.subscriptionbilling.dunning;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.mockito.Mockito.verify;

/**
 * Unit coverage of {@link SuspendAndScheduleRetryDunningHandoff}: it forwards a failed
 * charge to {@link SubscriptionSuspensionPort#suspend} with the failed charge's
 * Subscription and Invoice ids (unchanged from the placeholder it replaces), then
 * schedules the day-1 Dunning retry via {@link SubscriptionSuspensionPort#scheduleRetry}
 * using {@link DunningSchedule}'s day-1 offset computed from the failure instant.
 * Whether the suspension or the retry scheduling itself succeeds against a given
 * Subscription state is the port implementation's own contract, covered where that
 * implementation lives.
 */
@ExtendWith(MockitoExtension.class)
class SuspendAndScheduleRetryDunningHandoffTest {

    @Mock
    private SubscriptionSuspensionPort subscriptionSuspensionPort;

    @Test
    void onChargeFailedSuspendsTheSubscriptionThroughThePortUsingTheInvoiceIdAsTheCorrelationId() {
        UUID subscriptionId = UUID.randomUUID();
        UUID invoiceId = UUID.randomUUID();
        Instant failedAt = Instant.parse("2027-01-01T10:00:00Z");

        new SuspendAndScheduleRetryDunningHandoff(subscriptionSuspensionPort)
                .onChargeFailed(subscriptionId, invoiceId, failedAt);

        verify(subscriptionSuspensionPort).suspend(subscriptionId, invoiceId.toString());
    }

    @Test
    void onChargeFailedSchedulesTheDayOneRetryComputedFromTheFailureInstant() {
        UUID subscriptionId = UUID.randomUUID();
        UUID invoiceId = UUID.randomUUID();
        Instant failedAt = Instant.parse("2027-01-01T10:00:00Z");

        new SuspendAndScheduleRetryDunningHandoff(subscriptionSuspensionPort)
                .onChargeFailed(subscriptionId, invoiceId, failedAt);

        verify(subscriptionSuspensionPort).scheduleRetry(subscriptionId, LocalDate.of(2027, 1, 2));
    }

    @Test
    void onChargeFailedResolvesTheDayOneRetryDateInUtcRegardlessOfHowCloseTheFailureIsToUtcMidnight() {
        UUID subscriptionId = UUID.randomUUID();
        UUID invoiceId = UUID.randomUUID();
        Instant failedAt = Instant.parse("2027-03-15T23:30:00Z");

        new SuspendAndScheduleRetryDunningHandoff(subscriptionSuspensionPort)
                .onChargeFailed(subscriptionId, invoiceId, failedAt);

        verify(subscriptionSuspensionPort).scheduleRetry(subscriptionId, LocalDate.of(2027, 3, 16));
    }
}
