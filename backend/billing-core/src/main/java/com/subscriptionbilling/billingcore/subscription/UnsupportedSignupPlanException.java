package com.subscriptionbilling.billingcore.subscription;

import java.util.UUID;

/**
 * A signup request resolved to a real, available Plan, but one this ticket's scope
 * doesn't yet implement: only the free-plan ([*] to active, no Billing Cycle) path
 * exists so far. Trial and immediate-paid signup are separate, later tickets — this
 * exception is what stands in for those code paths until they land, so a paid-plan
 * request fails loudly instead of being silently mishandled as a free signup.
 */
public class UnsupportedSignupPlanException extends RuntimeException {

    public UnsupportedSignupPlanException(UUID planId) {
        super("Signup for plan " + planId + " is not yet supported (only free-plan signup is implemented)");
    }
}
