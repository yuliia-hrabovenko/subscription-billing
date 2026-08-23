package com.subscriptionbilling.invoicing.invoice;

/** The resolved outcome of a single charge try; a PaymentAttempt is recorded after the gateway responds. */
public enum PaymentAttemptStatus {
    SUCCEEDED,
    FAILED
}
