package com.subscriptionbilling.invoicing.invoice;

/** Thrown when a client-supplied list-endpoint cursor is not one this API ever issued. */
public class InvalidCursorException extends RuntimeException {

    public InvalidCursorException(String cursor) {
        super("Cursor is not valid: " + cursor);
    }
}
