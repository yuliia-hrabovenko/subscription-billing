package com.subscriptionbilling.billingcore.subscription;

import java.util.UUID;

/**
 * Outcome of a signup: the new Subscription's identity/state, the Plan it's on, and a
 * freshly issued bearer token for its Customer (ADR-0003) — the one response shape a
 * pre-auth signup caller needs to become an authenticated caller for every subsequent
 * request.
 */
public record SubscriptionSignupResult(UUID subscriptionId, SubscriptionState state, UUID planId, String accessToken) {
}
