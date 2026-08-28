package com.subscriptionbilling.invoicing.invoice;

import java.time.Instant;
import java.util.UUID;

/**
 * Read model for one {@link PaymentAttempt}, consumed outside this module.
 *
 * @param id          the PaymentAttempt's identity
 * @param status      the gateway's resolved outcome for this try
 * @param attemptedAt the instant the gateway resolved this try
 */
public record PaymentAttemptView(UUID id, PaymentAttemptStatus status, Instant attemptedAt) {

    static PaymentAttemptView from(PaymentAttempt attempt) {
        return new PaymentAttemptView(attempt.getId(), attempt.getStatus(), attempt.getAttemptedAt());
    }
}
