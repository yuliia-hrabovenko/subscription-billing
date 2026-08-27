package com.subscriptionbilling.dunning;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * The fixed Dunning retry offsets — day 1, day 3, day 7 — computed relative to the
 * Invoice's initial failed Payment Attempt timestamp. The offsets are fixed and not
 * configurable per Invoice or Subscription.
 *
 * @param initialFailureAt the instant the Invoice's initial Payment Attempt failed
 */
public record DunningSchedule(Instant initialFailureAt) {

    /**
     * @return the day-1 scheduled retry instant
     */
    public Instant dayOneRetryAt() {
        return initialFailureAt.plus(1, ChronoUnit.DAYS);
    }

    /**
     * @return the day-3 scheduled retry instant
     */
    public Instant dayThreeRetryAt() {
        return initialFailureAt.plus(3, ChronoUnit.DAYS);
    }

    /**
     * @return the day-7 scheduled retry instant
     */
    public Instant daySevenRetryAt() {
        return initialFailureAt.plus(7, ChronoUnit.DAYS);
    }
}
