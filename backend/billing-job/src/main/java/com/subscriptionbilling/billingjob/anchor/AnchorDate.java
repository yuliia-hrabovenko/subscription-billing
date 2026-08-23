package com.subscriptionbilling.billingjob.anchor;

import java.time.LocalDate;
import java.time.YearMonth;

/**
 * The day-of-month a paid Subscription's Billing Cycle recurs on. On its own it is just
 * a number 1-31; {@link #resolveFor(YearMonth)} is where the clamping happens: a day
 * past a target month's last day (e.g. 31 in February) clamps to that month's last day.
 * The original day-of-month is never overwritten by a clamp, only reapplied fresh each
 * month, which is what makes it revert once a long-enough month recurs (Jan 31 -> Feb
 * 28/29 -> Mar 31 -> Apr 30 -> May 31 -> ...).
 */
public record AnchorDate(int dayOfMonth) {

    public AnchorDate {
        if (dayOfMonth < 1 || dayOfMonth > 31) {
            throw new IllegalArgumentException("dayOfMonth must be between 1 and 31, was " + dayOfMonth);
        }
    }

    /**
     * @param date the day a Billing Cycle first anchors to
     * @return an AnchorDate carrying {@code date}'s day-of-month
     */
    public static AnchorDate of(LocalDate date) {
        return new AnchorDate(date.getDayOfMonth());
    }

    /**
     * @param month the target Billing Cycle's month
     * @return this AnchorDate's day-of-month, clamped to {@code month}'s last valid day
     */
    public LocalDate resolveFor(YearMonth month) {
        return month.atDay(Math.min(dayOfMonth, month.lengthOfMonth()));
    }

    /**
     * @param currentBillingDate the Billing Cycle date just charged
     * @return the following month's Billing Cycle date, per {@link #resolveFor(YearMonth)}
     */
    public LocalDate next(LocalDate currentBillingDate) {
        return resolveFor(YearMonth.from(currentBillingDate).plusMonths(1));
    }
}
