package com.subscriptionbilling.billingjob.reconciliation;

/**
 * Result of a {@link PaymentOutcomeReconciler} call, telling a caller (a
 * payment-succeeded/failed webhook handler) what happened.
 */
public enum ReconciliationOutcome {

    /** No PaymentAttempt was already recorded for this gateway reference; the reported outcome was applied. */
    APPLIED,

    /** The synchronous charge path already recorded this exact outcome; nothing more was done. */
    ALREADY_RECORDED,

    /** A PaymentAttempt already recorded the opposite outcome for this gateway reference; nothing was applied. */
    CONFLICTING_OUTCOME
}
