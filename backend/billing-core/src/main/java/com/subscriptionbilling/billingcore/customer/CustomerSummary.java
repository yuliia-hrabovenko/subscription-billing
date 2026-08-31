package com.subscriptionbilling.billingcore.customer;

import java.time.Instant;
import java.util.UUID;

/**
 * Read model for the Admin customer listing. Deliberately excludes {@link
 * Customer#getPaymentMethodToken()} — a gateway-provided reference an Admin's
 * read-only visibility scope has no need to see, even though it isn't a raw card
 * number.
 */
public record CustomerSummary(UUID id, String email, Instant createdAt) {

    static CustomerSummary from(Customer customer) {
        return new CustomerSummary(customer.getId(), customer.getEmail(), customer.getCreatedAt());
    }
}
