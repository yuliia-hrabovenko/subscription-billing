package com.subscriptionbilling.invoicing.invoice;

import com.subscriptionbilling.billingjob.invoicing.ChargeRecordingPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * This module's implementation of the billing-job module's {@link ChargeRecordingPort}
 * contract: creates the Invoice for a Billing Cycle on its first charge attempt (per
 * Invariant 6, "one Invoice per (subscription, billing period)") and attaches every
 * subsequent attempt to that same Invoice.
 */
@Component
public class ChargeRecordingAdapter implements ChargeRecordingPort {

    private static final Logger log = LoggerFactory.getLogger(ChargeRecordingAdapter.class);

    private final InvoiceRepository invoiceRepository;
    private final PaymentAttemptRepository paymentAttemptRepository;

    public ChargeRecordingAdapter(InvoiceRepository invoiceRepository, PaymentAttemptRepository paymentAttemptRepository) {
        this.invoiceRepository = invoiceRepository;
        this.paymentAttemptRepository = paymentAttemptRepository;
    }

    @Override
    @Transactional
    public void recordSuccessfulCharge(UUID subscriptionId, LocalDate billingPeriod, UUID priceVersionId,
                                        String gatewayTransactionId, Instant attemptedAt) {
        Invoice invoice = invoiceRepository.findBySubscriptionIdAndBillingPeriod(subscriptionId, billingPeriod)
                .orElseGet(() -> invoiceRepository.save(
                        new Invoice(UUID.randomUUID(), subscriptionId, billingPeriod, priceVersionId)));
        paymentAttemptRepository.save(
                new PaymentAttempt(UUID.randomUUID(), invoice, PaymentAttemptStatus.SUCCEEDED, attemptedAt));
        log.info("Charge succeeded for subscription {} billing period {}: invoice {}, gateway transaction {}",
                subscriptionId, billingPeriod, invoice.getId(), gatewayTransactionId);
    }
}
