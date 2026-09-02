package com.subscriptionbilling.api.subscription;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * {@code POST /api/v1/subscriptions}'s request body, covering all three signup paths.
 * {@code email}/{@code password} are accepted but ignored when a bearer token
 * identifies an already-existing, re-subscribing Customer (their credential was
 * already set on their original signup) — simpler than making them conditionally
 * required. {@code useTrial}/{@code paymentMethodToken} are required only for a paid
 * Plan, enforced as a domain rule rather than shape validation. {@code useTrial} is
 * boxed rather than primitive specifically so an omitted field (a free-Plan signup body
 * never sends it) binds to null instead of failing deserialization.
 */
public record SignupRequest(@NotNull UUID planId, @NotBlank @Email String email, Boolean useTrial,
                             String paymentMethodToken, @NotBlank @Size(min = 8) String password) {

    /**
     * @return true only if the request explicitly opted into a Trial; an omitted or
     *         {@code false} value both mean "no Trial"
     */
    public boolean useTrialOrDefault() {
        return Boolean.TRUE.equals(useTrial);
    }
}
