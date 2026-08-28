package com.subscriptionbilling.invoicing.invoice;

import com.subscriptionbilling.billingjob.invoicing.PriceVersionSnapshot;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Read model for one {@link Invoice} as a list item — no PaymentAttempt history, so
 * building a page of these never costs one query per Invoice. {@code planName} and
 * {@code amount} come from the Invoice's snapshotted PriceVersion, never "the Plan's
 * current price" (Invariant 9).
 */
public record InvoiceSummary(UUID id, LocalDate billingPeriod, String planName, BigDecimal amount,
                              InvoiceStatus status, Instant createdAt) {

    static InvoiceSummary from(Invoice invoice, PriceVersionSnapshot priceVersionSnapshot) {
        return new InvoiceSummary(invoice.getId(), invoice.getBillingPeriod(), priceVersionSnapshot.planName(),
                priceVersionSnapshot.amount(), invoice.getStatus(), invoice.getCreatedAt());
    }
}
