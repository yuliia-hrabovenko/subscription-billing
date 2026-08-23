package com.subscriptionbilling.invoicing.invoice;

/**
 * {@code PAID}/{@code FAILED} are terminal, reached once a PaymentAttempt succeeds or
 * every attempt for the cycle is exhausted. {@code OPEN} covers everything before that,
 * including a cycle with a failed first attempt still awaiting a Dunning retry.
 */
public enum InvoiceStatus {
    OPEN,
    PAID,
    FAILED
}
