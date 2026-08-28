package com.subscriptionbilling.invoicing.invoice;

import java.util.List;

/**
 * One reverse-chronological page of a Subscription's Invoices.
 *
 * @param items      this page's Invoices, newest first
 * @param nextCursor opaque token to pass back to fetch the next page, or null if this
 *                   was the last page
 */
public record InvoicePage(List<InvoiceSummary> items, String nextCursor) {
}
