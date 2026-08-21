package com.subscriptionbilling.billingcore.plan;

import java.util.UUID;

/**
 * A requested Plan can't be selected for a new signup: it doesn't exist, is retired
 * (Invariant 10), or has no {@link PriceVersion} currently in effect. These are
 * collapsed into one exception rather than three: from a signup request's perspective
 * they're the same outcome (this Plan isn't one you can sign up for today), and {@link
 * com.subscriptionbilling.billingcore.plan.PlanCatalogService#findAvailablePlan}
 * already applies the identical exclusion rule the public catalog listing uses, so the
 * two surfaces can never disagree about which Plans are selectable.
 */
public class PlanUnavailableForSignupException extends RuntimeException {

    public PlanUnavailableForSignupException(UUID planId) {
        super("Plan " + planId + " is not available for signup");
    }
}
