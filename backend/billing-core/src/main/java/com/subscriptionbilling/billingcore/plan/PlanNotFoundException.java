package com.subscriptionbilling.billingcore.plan;

import java.util.UUID;

/**
 * An Admin request targeted a Plan id that doesn't exist. Unlike the
 * Customer-facing {@link PlanUnavailableForSignupException} (which collapses
 * not-found/retired/unpriced into one signup-shaped rejection), this is a plain 404 —
 * an Admin already has full catalog visibility, so there's no ID-enumeration concern
 * to defend against by hiding not-found behind a different status.
 */
public class PlanNotFoundException extends RuntimeException {

    public PlanNotFoundException(UUID planId) {
        super("Plan " + planId + " does not exist");
    }
}
