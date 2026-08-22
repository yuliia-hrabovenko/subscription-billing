package com.subscriptionbilling.billingcore.subscription;

import java.time.Instant;
import java.util.UUID;

/**
 * Outcome of a signup. {@code trialEndsAt} and {@code billingCycleAnchor} are mutually
 * exclusive, set only for their respective signup path, and both null for a free-Plan
 * signup.
 */
public record SubscriptionSignupResult(UUID subscriptionId, SubscriptionState state, UUID planId, String accessToken,
                                        Instant trialEndsAt, Instant billingCycleAnchor) {
}
