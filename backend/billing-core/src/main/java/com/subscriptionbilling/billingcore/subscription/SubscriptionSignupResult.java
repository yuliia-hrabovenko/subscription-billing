package com.subscriptionbilling.billingcore.subscription;

import java.time.Instant;
import java.util.UUID;

/**
 * Outcome of a signup: the new Subscription's identity/state, the Plan it's on, a
 * freshly issued bearer token for its Customer — the one response shape a pre-auth
 * signup caller needs to become an authenticated caller for every subsequent request —
 * and, depending on which of the three signup paths was taken, either {@code
 * trialEndsAt} (Trial) or {@code billingCycleAnchor} (immediate-paid). Exactly one of
 * those two is non-null for a paid-Plan signup, and both are null for a free-Plan
 * signup.
 */
public record SubscriptionSignupResult(UUID subscriptionId, SubscriptionState state, UUID planId, String accessToken,
                                        Instant trialEndsAt, Instant billingCycleAnchor) {
}
