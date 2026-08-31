package com.subscriptionbilling.api.customer;

import com.subscriptionbilling.billingcore.customer.CustomerSummary;

import java.time.Instant;
import java.util.UUID;

/** One row of {@code GET /api/v1/admin/customers}'s response. */
public record CustomerSummaryResponse(UUID id, String email, Instant createdAt) {

    static CustomerSummaryResponse from(CustomerSummary summary) {
        return new CustomerSummaryResponse(summary.id(), summary.email(), summary.createdAt());
    }
}
