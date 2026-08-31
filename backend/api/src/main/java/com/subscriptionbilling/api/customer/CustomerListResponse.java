package com.subscriptionbilling.api.customer;

import com.subscriptionbilling.billingcore.customer.CustomerPage;

import java.util.List;

/** {@code GET /api/v1/admin/customers}'s response: one reverse-chronological page. */
public record CustomerListResponse(List<CustomerSummaryResponse> items, String nextCursor) {

    static CustomerListResponse from(CustomerPage page) {
        return new CustomerListResponse(page.items().stream().map(CustomerSummaryResponse::from).toList(), page.nextCursor());
    }
}
