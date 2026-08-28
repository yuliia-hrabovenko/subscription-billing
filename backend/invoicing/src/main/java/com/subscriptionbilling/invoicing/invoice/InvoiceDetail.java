package com.subscriptionbilling.invoicing.invoice;

import com.subscriptionbilling.billingjob.invoicing.PriceVersionSnapshot;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Read model for one {@link Invoice} fetched individually, including its full {@link
 * PaymentAttempt} history (oldest first — one entry on the happy path, up to four after
 * Dunning). {@code planName} and {@code amount} come from the Invoice's snapshotted
 * PriceVersion, never "the Plan's current price" (Invariant 9).
 */
public record InvoiceDetail(UUID id, UUID subscriptionId, LocalDate billingPeriod, String planName, BigDecimal amount,
                             InvoiceStatus status, Instant createdAt, List<PaymentAttemptView> paymentAttempts) {

    static InvoiceDetail from(Invoice invoice, PriceVersionSnapshot priceVersionSnapshot, List<PaymentAttempt> attempts) {
        return new InvoiceDetail(invoice.getId(), invoice.getSubscriptionId(), invoice.getBillingPeriod(),
                priceVersionSnapshot.planName(), priceVersionSnapshot.amount(), invoice.getStatus(), invoice.getCreatedAt(),
                attempts.stream().map(PaymentAttemptView::from).toList());
    }
}
