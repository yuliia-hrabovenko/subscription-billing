package com.subscriptionbilling.dunning;

import com.subscriptionbilling.billingjob.dunning.DunningHandoff;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * The real {@link DunningHandoff}: suspends the Subscription immediately, no grace
 * period, matching the domain model's first-failed-charge-suspends business rule, and
 * schedules the day-1 Dunning retry by moving {@code due_date} so the billing job's
 * existing due-date query ({@code due_date <= today}) picks the Subscription back up,
 * with no new query needed. Day-3/day-7 rescheduling and the self-service retry path
 * are built by later tickets in this spec.
 */
@Component
public class SuspendAndScheduleRetryDunningHandoff implements DunningHandoff {

    private final SubscriptionSuspensionPort subscriptionSuspensionPort;

    public SuspendAndScheduleRetryDunningHandoff(SubscriptionSuspensionPort subscriptionSuspensionPort) {
        this.subscriptionSuspensionPort = subscriptionSuspensionPort;
    }

    /**
     * Suspends the Subscription identified by {@code subscriptionId}, then schedules
     * its day-1 Dunning retry, computed from {@code failedAt} via {@link
     * DunningSchedule}. {@code invoiceId} is passed through as the correlation ID for
     * the suspension, tying it back to the specific failed Payment Attempt.
     *
     * @param subscriptionId the Subscription to suspend and schedule the retry for
     * @param invoiceId      the Invoice the failed Payment Attempt belongs to
     * @param failedAt       the instant this initial charge failure was resolved
     */
    @Override
    public void onChargeFailed(UUID subscriptionId, UUID invoiceId, Instant failedAt) {
        subscriptionSuspensionPort.suspend(subscriptionId, invoiceId.toString());

        LocalDate dayOneRetryDueDate = new DunningSchedule(failedAt).dayOneRetryAt().atZone(ZoneOffset.UTC).toLocalDate();
        subscriptionSuspensionPort.scheduleRetry(subscriptionId, dayOneRetryDueDate);
    }
}
