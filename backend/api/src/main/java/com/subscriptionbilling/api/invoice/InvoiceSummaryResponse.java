package com.subscriptionbilling.api.invoice;

import com.subscriptionbilling.invoicing.invoice.InvoiceSummary;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** One entry in {@code GET /api/v1/subscriptions/{id}/invoices}'s list. */
public record InvoiceSummaryResponse(UUID id, LocalDate billingPeriod, String planName, BigDecimal amount,
                                      String status, Instant createdAt) {

    static InvoiceSummaryResponse from(InvoiceSummary summary) {
        return new InvoiceSummaryResponse(summary.id(), summary.billingPeriod(), summary.planName(), summary.amount(),
                summary.status().name(), summary.createdAt());
    }
}
