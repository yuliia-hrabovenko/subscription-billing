package com.subscriptionbilling.api.invoice;

import com.subscriptionbilling.invoicing.invoice.InvoiceDetail;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** {@code GET /api/v1/invoices/{id}}'s response: the Invoice plus its full PaymentAttempt history. */
public record InvoiceResponse(UUID id, UUID subscriptionId, LocalDate billingPeriod, String planName, BigDecimal amount,
                               String status, Instant createdAt, List<PaymentAttemptResponse> paymentAttempts) {

    static InvoiceResponse from(InvoiceDetail detail) {
        return new InvoiceResponse(detail.id(), detail.subscriptionId(), detail.billingPeriod(), detail.planName(),
                detail.amount(), detail.status().name(), detail.createdAt(),
                detail.paymentAttempts().stream().map(PaymentAttemptResponse::from).toList());
    }
}
