package com.subscriptionbilling.billingjob.dunning;

/**
 * Result of {@link DunningHandoff#onRetryFailed}, telling the billing job which of the
 * two bounded-retry outcomes applied.
 */
public enum DunningRetryOutcome {

    /** The retry bound was not yet reached; the next Dunning offset was scheduled. */
    RESCHEDULED,

    /** The retry bound was reached (the 3rd scheduled retry's failure); the Subscription was canceled. */
    CANCELED
}
