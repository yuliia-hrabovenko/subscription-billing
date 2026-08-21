package com.subscriptionbilling.api.subscription;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * {@code POST /api/v1/subscriptions}'s request body. {@code email} is only used to
 * mint a brand-new Customer (no bearer token presented); when a token identifies an
 * already-existing, re-subscribing Customer, it's accepted but ignored
 * rather than made conditionally-required — simpler than branching validation on
 * whether a header was sent, and harmless either way.
 */
public record SignupRequest(@NotNull UUID planId, @NotBlank @Email String email) {
}
