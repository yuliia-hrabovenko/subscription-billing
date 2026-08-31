package com.subscriptionbilling.billingcore.customer;

import java.util.List;

/**
 * One page of {@code GET /api/v1/admin/customers}, newest first.
 *
 * @param items      this page's Customers
 * @param nextCursor opaque token to pass back to fetch the next page, or null if this
 *                   was the last page
 */
public record CustomerPage(List<CustomerSummary> items, String nextCursor) {
}
