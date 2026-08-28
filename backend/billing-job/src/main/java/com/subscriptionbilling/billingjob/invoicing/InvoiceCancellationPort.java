package com.subscriptionbilling.billingjob.invoicing;

import java.time.LocalDate;
import java.util.UUID;

/**
 * The seam a direct Subscription cancellation ends an Invoice's still-open Dunning retry
 * sequence through, so billing-core drives the cancellation without owning the Invoicing
 * module's data model. An implementation resolves the Invoice already open for {@code
 * (subscriptionId, billingPeriod)} and marks it terminal.
 */
public interface InvoiceCancellationPort {

    /**
     * Marks the still-open Invoice for {@code (subscriptionId, billingPeriod)} failed:
     * a direct cancellation while suspended, before Dunning's retries are exhausted,
     * ends that Invoice's retry sequence early — terminal here means "no more attempts
     * are coming," not "every bounded retry was used."
     *
     * @param subscriptionId the Subscription being canceled
     * @param billingPeriod  the still-open Billing Cycle whose Invoice is failed
     */
    void failStillOpenInvoice(UUID subscriptionId, LocalDate billingPeriod);
}
