package com.subscriptionbilling.dunning;

import com.subscriptionbilling.billingjob.dunning.DunningHandoff;
import com.subscriptionbilling.billingjob.dunning.DunningRetryOutcome;
import com.subscriptionbilling.billingjob.invoicing.DunningRetryState;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * The real {@link DunningHandoff}: suspends the Subscription immediately, no grace
 * period, matching the domain model's first-failed-charge-suspends business rule, and
 * drives the bounded day 1/3/7 retry loop by moving {@code due_date} so the billing
 * job's existing due-date query ({@code due_date <= today}) picks the Subscription back
 * up, with no new query needed. {@link #onRetryFailed} is also the decision a
 * self-service retry (a Customer retrying payment directly rather than waiting for the
 * next scheduled attempt) goes through, via
 * {@link com.subscriptionbilling.billingjob.dunning.DunningRetryCharge} — so both
 * triggers share the same reschedule-vs-cancel outcome.
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
     * @param billingPeriod  the Billing Cycle date whose charge just failed
     * @param failedAt       the instant this initial charge failure was resolved
     */
    @Override
    public void onChargeFailed(UUID subscriptionId, UUID invoiceId, LocalDate billingPeriod, Instant failedAt) {
        subscriptionSuspensionPort.suspend(subscriptionId, billingPeriod, invoiceId.toString());

        LocalDate dayOneRetryDueDate = new DunningSchedule(failedAt).dayOneRetryAt().atZone(ZoneOffset.UTC).toLocalDate();
        subscriptionSuspensionPort.scheduleRetry(subscriptionId, dayOneRetryDueDate);
    }

    /**
     * Cancels the Subscription if {@code retryState} reports the retry bound reached
     * (the day-7/3rd retry's failure), or otherwise reschedules the next Dunning offset
     * — day 3 after a {@code retriesUsed == 1} (day-1) failure, day 7 after a {@code
     * retriesUsed == 2} (day-3) failure — computed from {@link
     * DunningRetryState#initialFailureAt()} via {@link DunningSchedule}, never from
     * {@code failedAt}. {@code invoiceId} is passed through as the correlation ID for a
     * resulting cancellation.
     *
     * @param subscriptionId the Subscription the failed retry was attempted against
     * @param invoiceId      the Invoice the failed Payment Attempt belongs to
     * @param retryState     the Invoice's retry bookkeeping immediately after this
     *                       failed attempt was recorded
     * @param failedAt       the instant this retry failure was resolved (unused when
     *                       rescheduling — the schedule is anchored to the Invoice's
     *                       original failure, not to this attempt)
     */
    @Override
    public DunningRetryOutcome onRetryFailed(UUID subscriptionId, UUID invoiceId, DunningRetryState retryState, Instant failedAt) {
        if (retryState.retriesExhausted()) {
            subscriptionSuspensionPort.cancel(subscriptionId, invoiceId.toString());
            return DunningRetryOutcome.CANCELED;
        }

        DunningSchedule schedule = new DunningSchedule(retryState.initialFailureAt());
        Instant nextRetryAt = retryState.retriesUsed() == 1 ? schedule.dayThreeRetryAt() : schedule.daySevenRetryAt();
        subscriptionSuspensionPort.scheduleRetry(subscriptionId, nextRetryAt.atZone(ZoneOffset.UTC).toLocalDate());
        return DunningRetryOutcome.RESCHEDULED;
    }
}
