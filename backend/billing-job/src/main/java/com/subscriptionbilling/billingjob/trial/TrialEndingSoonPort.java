package com.subscriptionbilling.billingjob.trial;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The seam the billing job's trial-ending-soon scan selects candidates through and
 * notifies them by, so the job never depends on billing-core's Subscription aggregate or
 * persistence directly. An implementation owns both the candidate query and how a
 * notification actually gets recorded.
 */
public interface TrialEndingSoonPort {

    /**
     * Finds every trialing Subscription approaching its Trial end that hasn't already
     * been notified.
     *
     * @param leadTimeCutoff the latest {@code trialEndsAt} to select — a Subscription is
     *                       a candidate when its Trial ends on or before this instant
     * @return the ids of every matching Subscription, in no particular order
     */
    List<UUID> findTrialsEndingSoon(Instant leadTimeCutoff);

    /**
     * Notifies a single Subscription that its Trial is ending soon, and records that the
     * notification was sent so {@link #findTrialsEndingSoon} never selects it again.
     *
     * @param subscriptionId the Subscription to notify
     * @param notifiedAt     the instant this notification was enqueued
     */
    void notifyTrialEndingSoon(UUID subscriptionId, Instant notifiedAt);
}
