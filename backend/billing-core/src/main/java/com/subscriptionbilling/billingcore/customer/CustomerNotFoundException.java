package com.subscriptionbilling.billingcore.customer;

import java.util.UUID;

/**
 * An Admin request targeted a Customer id that doesn't exist. A plain
 * 404 — an Admin already has full visibility, so there's no ID-enumeration concern to
 * defend against here (contrast the Customer-facing 403-not-404 conventions elsewhere
 * in this API, e.g. {@code SubscriptionAccessDeniedException}).
 */
public class CustomerNotFoundException extends RuntimeException {

    public CustomerNotFoundException(UUID customerId) {
        super("Customer " + customerId + " does not exist");
    }
}
