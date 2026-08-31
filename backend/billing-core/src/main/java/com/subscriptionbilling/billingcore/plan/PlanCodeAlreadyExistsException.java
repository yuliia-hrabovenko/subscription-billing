package com.subscriptionbilling.billingcore.plan;

/**
 * An Admin ({@code POST /api/v1/admin/plans}) tried to create a Plan whose
 * {@code code} already exists. A dedicated 409 from a pre-check, distinct from the
 * generic {@code DataIntegrityViolationException} fallback that still guards the race
 * this application-layer check can't catch — same pattern as {@code
 * DuplicateSubscriptionException}.
 */
public class PlanCodeAlreadyExistsException extends RuntimeException {

    public PlanCodeAlreadyExistsException(String code) {
        super("Plan code '" + code + "' already exists");
    }
}
