package com.subscriptionbilling.api.invoice;

import com.subscriptionbilling.invoicing.invoice.PaymentAttemptView;

import java.time.Instant;
import java.util.UUID;

/** One entry in {@code GET /api/v1/invoices/{id}}'s PaymentAttempt history. */
public record PaymentAttemptResponse(UUID id, String status, Instant attemptedAt) {

    static PaymentAttemptResponse from(PaymentAttemptView view) {
        return new PaymentAttemptResponse(view.id(), view.status().name(), view.attemptedAt());
    }
}
