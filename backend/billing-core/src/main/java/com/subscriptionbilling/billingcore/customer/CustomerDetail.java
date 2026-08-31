package com.subscriptionbilling.billingcore.customer;

import com.subscriptionbilling.billingcore.subscription.AdminSubscriptionView;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Read model for {@code GET /api/v1/admin/customers/{id}}: the Customer
 * plus every Subscription they've ever had, so an Admin doesn't need a second lookup
 * to find a Customer's Subscription ids before drilling into one.
 */
public record CustomerDetail(UUID id, String email, Instant createdAt, List<AdminSubscriptionView> subscriptions) {
}
