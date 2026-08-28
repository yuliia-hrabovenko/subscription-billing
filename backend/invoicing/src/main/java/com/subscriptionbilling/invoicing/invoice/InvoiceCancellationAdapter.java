package com.subscriptionbilling.invoicing.invoice;

import com.subscriptionbilling.billingjob.invoicing.InvoiceCancellationPort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.UUID;

/**
 * This module's implementation of the billing-job module's {@link
 * InvoiceCancellationPort} contract, backed by {@link Invoice#markFailed()}.
 */
@Component
public class InvoiceCancellationAdapter implements InvoiceCancellationPort {

    private final InvoiceRepository invoiceRepository;

    public InvoiceCancellationAdapter(InvoiceRepository invoiceRepository) {
        this.invoiceRepository = invoiceRepository;
    }

    /**
     * @throws IllegalStateException if no Invoice exists for {@code (subscriptionId,
     *         billingPeriod)}
     */
    @Override
    @Transactional
    public void failStillOpenInvoice(UUID subscriptionId, LocalDate billingPeriod) {
        Invoice invoice = invoiceRepository.findBySubscriptionIdAndBillingPeriod(subscriptionId, billingPeriod)
                .orElseThrow(() -> new IllegalStateException(
                        "No Invoice for subscription " + subscriptionId + " billing period " + billingPeriod));
        invoice.markFailed();
        invoiceRepository.save(invoice);
    }
}
