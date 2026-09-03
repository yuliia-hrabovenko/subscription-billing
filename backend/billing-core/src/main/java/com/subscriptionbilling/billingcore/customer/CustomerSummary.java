package com.subscriptionbilling.billingcore.customer;

import java.time.Instant;
import java.util.UUID;

/**
 * Read model for the Admin customer listing. Deliberately excludes any card-on-file
 * detail — an Admin's read-only visibility scope has no need to see it, and it isn't
 * carried on {@link Customer} in the first place (see ADR-0011).
 */
public record CustomerSummary(UUID id, String email, Instant createdAt) {

    static CustomerSummary from(Customer customer) {
        return new CustomerSummary(customer.getId(), customer.getEmail(), customer.getCreatedAt());
    }
}
