package com.subscriptionbilling.invoicing.invoice;

import java.util.UUID;

/**
 * Thrown for both "no such Invoice" and "exists, but its Subscription belongs to a
 * different Customer" — deliberately the same exception for both, mirroring {@code
 * SubscriptionAccessDeniedException}: distinguishing them at the HTTP layer (404 vs.
 * 403) would let a caller enumerate which Invoice/Subscription ids exist.
 */
public class InvoiceAccessDeniedException extends RuntimeException {

    public InvoiceAccessDeniedException(UUID id) {
        super("Invoice or Subscription " + id + " does not belong to the authenticated Customer");
    }
}
