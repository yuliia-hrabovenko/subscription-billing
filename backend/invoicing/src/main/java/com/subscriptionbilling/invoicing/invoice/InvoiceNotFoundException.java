package com.subscriptionbilling.invoicing.invoice;

import java.util.UUID;

/**
 * An Admin request targeted an Invoice id that doesn't exist. Unlike {@link
 * InvoiceAccessDeniedException} (which collapses not-found/not-owned into one 403 to
 * stop a non-owner enumerating ids), this is a plain 404 — an Admin already has full
 * visibility, so there's no ID-enumeration concern to defend against here.
 */
public class InvoiceNotFoundException extends RuntimeException {

    public InvoiceNotFoundException(UUID invoiceId) {
        super("Invoice " + invoiceId + " does not exist");
    }
}
