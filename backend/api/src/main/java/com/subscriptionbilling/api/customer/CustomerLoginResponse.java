package com.subscriptionbilling.api.customer;

import com.subscriptionbilling.billingcore.auth.CustomerLoginResult;

import java.util.UUID;

/**
 * {@code POST /api/v1/customers/login}'s response: the bearer token for every
 * subsequent request, plus the Customer's current Subscription id if they have one
 * ({@code null} otherwise — see {@link CustomerLoginResult}).
 */
public record CustomerLoginResponse(String accessToken, UUID subscriptionId) {

    static CustomerLoginResponse from(CustomerLoginResult result) {
        return new CustomerLoginResponse(result.accessToken(), result.subscriptionId());
    }
}
