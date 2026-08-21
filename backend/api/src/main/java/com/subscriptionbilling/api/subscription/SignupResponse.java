package com.subscriptionbilling.api.subscription;

import com.subscriptionbilling.billingcore.subscription.SubscriptionSignupResult;

import java.util.UUID;

/**
 * {@code POST /api/v1/subscriptions}'s response: the new Subscription plus the bearer
 * token that turns this pre-auth caller into an authenticated one for every
 * subsequent request.
 */
public record SignupResponse(UUID subscriptionId, String state, UUID planId, String accessToken) {

    static SignupResponse from(SubscriptionSignupResult result) {
        return new SignupResponse(result.subscriptionId(), result.state().name(), result.planId(), result.accessToken());
    }
}
