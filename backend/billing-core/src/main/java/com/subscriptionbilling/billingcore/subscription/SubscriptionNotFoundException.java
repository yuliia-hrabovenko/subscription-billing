package com.subscriptionbilling.billingcore.subscription;

import java.util.UUID;

/**
 * An Admin request targeted a Subscription id that doesn't exist. Unlike
 * {@link SubscriptionAccessDeniedException} (which collapses not-found/not-owned into
 * one 403 to stop a non-owner enumerating ids), this is a plain 404 — an Admin already
 * has full visibility, so there's no ID-enumeration concern to defend against here.
 */
public class SubscriptionNotFoundException extends RuntimeException {

    public SubscriptionNotFoundException(UUID subscriptionId) {
        super("Subscription " + subscriptionId + " does not exist");
    }
}
