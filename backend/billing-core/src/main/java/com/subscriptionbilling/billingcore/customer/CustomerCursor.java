package com.subscriptionbilling.billingcore.customer;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.UUID;

/**
 * Encodes/decodes the Admin customer list endpoint's opaque pagination cursor: the
 * {@code (createdAt, id)} of the last Customer on the previous page — same shape and
 * purpose as {@code InvoiceCursor} (invoicing module), duplicated rather than shared
 * since the two modules must not depend on each other just for this.
 */
final class CustomerCursor {

    record Position(Instant createdAt, UUID id) {
    }

    private CustomerCursor() {
    }

    static String encode(Instant createdAt, UUID id) {
        String raw = createdAt + "|" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * @param cursor the client-supplied cursor, or null/blank for a first-page request
     * @return the decoded position, or null if {@code cursor} was null/blank
     * @throws InvalidCustomerCursorException if {@code cursor} is non-blank but not one
     *         this API issued
     */
    static Position decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int separator = raw.indexOf('|');
            if (separator < 0) {
                throw new InvalidCustomerCursorException(cursor);
            }
            Instant createdAt = Instant.parse(raw.substring(0, separator));
            UUID id = UUID.fromString(raw.substring(separator + 1));
            return new Position(createdAt, id);
        } catch (IllegalArgumentException | DateTimeParseException malformed) {
            throw new InvalidCustomerCursorException(cursor);
        }
    }
}
