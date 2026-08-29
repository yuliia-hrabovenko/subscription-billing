package com.subscriptionbilling.billingjob.invoicing;

/**
 * The outcome already recorded against a PaymentAttempt found by {@link
 * ChargeRecordingPort#findRecordedOutcome}, for a webhook reconciliation caller to
 * compare against the outcome it's reporting.
 */
public enum RecordedChargeOutcome {
    SUCCEEDED,
    FAILED
}
