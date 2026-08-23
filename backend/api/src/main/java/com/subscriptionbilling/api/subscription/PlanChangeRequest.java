package com.subscriptionbilling.api.subscription;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * {@code POST /api/v1/subscriptions/{id}/plan-change}'s request body. Whether the
 * change applies immediately or is deferred to the next Billing Cycle is a domain rule
 * driven by the Subscription's current state, not something the caller specifies here.
 */
public record PlanChangeRequest(@NotNull UUID planId) {
}
