package com.subscriptionbilling.billingjob.due;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The seam the billing job selects due Subscriptions through, so it never depends on
 * billing-core's Subscription aggregate or persistence directly. An implementation owns
 * the actual due-date comparison against its own data model.
 */
public interface DueSubscriptionsPort {

    /**
     * Finds every Subscription due for a charge as of {@code asOf}.
     *
     * @param asOf the cutoff date; a Subscription is due when its due date is on or
     *             before this date (never only on it — this is what gives automatic
     *             catch-up for a missed run with no separate backfill path)
     * @return the ids of every due Subscription, in no particular order
     */
    List<UUID> findDueSubscriptionIds(LocalDate asOf);
}
