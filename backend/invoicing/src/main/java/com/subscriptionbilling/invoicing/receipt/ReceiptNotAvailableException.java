package com.subscriptionbilling.invoicing.receipt;

import com.subscriptionbilling.invoicing.invoice.InvoiceStatus;

import java.util.UUID;

/**
 * Thrown when a receipt is requested for an Invoice that isn't {@code paid} yet
 * ({@code open}) or never will be ({@code failed}) — a receipt is rendered only on the
 * transition to {@code paid}, so no other status ever has one.
 */
public class ReceiptNotAvailableException extends RuntimeException {

    public ReceiptNotAvailableException(UUID invoiceId, InvoiceStatus status) {
        super("Invoice " + invoiceId + " has no receipt: status is " + status + ", not PAID");
    }
}
