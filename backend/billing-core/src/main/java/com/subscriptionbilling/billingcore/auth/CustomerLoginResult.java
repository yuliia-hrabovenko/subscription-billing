package com.subscriptionbilling.billingcore.auth;

import java.util.UUID;

/**
 * Outcome of {@link CustomerAuthenticationService#authenticate}. {@code subscriptionId}
 * is null when the Customer has no current non-{@code canceled} Subscription.
 */
public record CustomerLoginResult(String accessToken, UUID subscriptionId) {
}
