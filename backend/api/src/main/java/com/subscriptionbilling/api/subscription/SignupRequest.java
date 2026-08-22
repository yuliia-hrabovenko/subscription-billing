package com.subscriptionbilling.api.subscription;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * {@code POST /api/v1/subscriptions}'s request body, covering all three signup paths.
 * {@code email} is only used to mint a brand-new Customer (no bearer token presented);
 * when a token identifies an already-existing, re-subscribing Customer, it's accepted
 * but ignored rather than made conditionally-required — simpler than branching
 * validation on whether a header was sent, and harmless either way. {@code useTrial}
 * and {@code paymentMethodToken} are ignored for a free-Plan signup and apply only to a
 * paid one: {@code useTrial} chooses between the Trial and immediate-paid path, and
 * {@code paymentMethodToken} is required for either paid path (enforced as a domain
 * rule, not shape validation, since whether it's required depends on which Plan was
 * requested). {@code useTrial} is boxed rather than a primitive specifically so an
 * omitted field (a free-Plan signup body never sends it) binds to null instead of
 * failing deserialization.
 */
public record SignupRequest(@NotNull UUID planId, @NotBlank @Email String email, Boolean useTrial,
                             String paymentMethodToken) {

    /**
     * @return true only if the request explicitly opted into a Trial; an omitted or
     *         {@code false} value both mean "no Trial"
     */
    public boolean useTrialOrDefault() {
        return Boolean.TRUE.equals(useTrial);
    }
}
