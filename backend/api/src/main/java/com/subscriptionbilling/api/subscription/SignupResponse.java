package com.subscriptionbilling.api.subscription;

import com.subscriptionbilling.billingcore.subscription.SubscriptionSignupResult;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code POST /api/v1/subscriptions}'s response: the new Subscription plus the bearer
 * token that turns this pre-auth caller into an authenticated one for every subsequent
 * request. {@code trialEndsAt} is set only for a Trial signup; {@code billingCycle} is
 * set only for an immediate-paid signup. Both are null for a free-Plan signup.
 */
public record SignupResponse(UUID subscriptionId, String state, UUID planId, String accessToken,
                              Instant trialEndsAt, BillingCycleRef billingCycle) {

    static SignupResponse from(SubscriptionSignupResult result) {
        return new SignupResponse(result.subscriptionId(), result.state().name(), result.planId(), result.accessToken(),
                result.trialEndsAt(), BillingCycleRef.from(result.billingCycleAnchor()));
    }
}
