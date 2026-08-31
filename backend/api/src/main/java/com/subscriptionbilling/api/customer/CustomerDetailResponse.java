package com.subscriptionbilling.api.customer;

import com.subscriptionbilling.api.subscription.AdminSubscriptionResponse;
import com.subscriptionbilling.billingcore.customer.CustomerDetail;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@code GET /api/v1/admin/customers/{id}}'s response: the Customer plus
 * every Subscription they've ever had, reusing {@link AdminSubscriptionResponse}'s
 * (public) shape rather than a second, near-identical DTO.
 */
public record CustomerDetailResponse(UUID id, String email, Instant createdAt,
                                      List<AdminSubscriptionResponse> subscriptions) {

    static CustomerDetailResponse from(CustomerDetail detail) {
        return new CustomerDetailResponse(detail.id(), detail.email(), detail.createdAt(),
                detail.subscriptions().stream().map(AdminSubscriptionResponse::from).toList());
    }
}
