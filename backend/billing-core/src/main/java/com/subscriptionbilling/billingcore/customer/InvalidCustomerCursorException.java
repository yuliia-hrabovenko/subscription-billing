package com.subscriptionbilling.billingcore.customer;

/**
 * A malformed or tampered {@code GET /api/v1/admin/customers} pagination cursor — a
 * client input error (400), not a not-found Customer. Mirrors invoicing's {@code
 * InvalidCursorException}.
 */
public class InvalidCustomerCursorException extends RuntimeException {

    public InvalidCustomerCursorException(String cursor) {
        super("Invalid pagination cursor: " + cursor);
    }
}
