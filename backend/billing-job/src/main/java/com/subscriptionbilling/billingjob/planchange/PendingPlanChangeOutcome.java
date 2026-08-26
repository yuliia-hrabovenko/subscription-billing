package com.subscriptionbilling.billingjob.planchange;

/**
 * Result of {@link PendingPlanChangePort#applyIfPending}, telling the billing job
 * whether and how to proceed with this cycle's charge attempt.
 */
public enum PendingPlanChangeOutcome {

    /** No Plan change was pending; the charge proceeds against the Subscription's current Plan. */
    NO_PENDING_CHANGE,

    /** A pending change to a still-paid Plan was applied; the charge proceeds at the new Plan's price. */
    APPLIED_PAID,

    /** A pending change to a free Plan was applied; no charge is attempted this cycle. */
    APPLIED_FREE
}
