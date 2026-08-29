package com.subscriptionbilling.invoicing.invoice;

import com.subscriptionbilling.billingjob.invoicing.ChargeAlreadyRecordedException;
import com.subscriptionbilling.billingjob.invoicing.ChargeRecordingPort;
import com.subscriptionbilling.billingjob.invoicing.DunningRetryState;
import com.subscriptionbilling.invoicing.receipt.ReceiptRenderingService;
import com.subscriptionbilling.notifications.outbox.OutboxEvent;
import com.subscriptionbilling.notifications.outbox.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * This module's implementation of the billing-job module's {@link ChargeRecordingPort}
 * contract: creates the Invoice for a Billing Cycle on its first charge attempt (per
 * Invariant 6, "one Invoice per (subscription, billing period)"), whether that first
 * attempt succeeds or fails, and attaches every subsequent attempt to that same Invoice.
 * The database's unique constraint on {@code (subscription_id, billing_period)} — not
 * this class's own find-then-create check — is the final word against two concurrent job
 * instances both racing to create the same Invoice; losing that race surfaces as {@link
 * ChargeAlreadyRecordedException} rather than a duplicate row or a raw {@link
 * DataIntegrityViolationException}. Also drives the Invoice's terminal status: a success
 * marks it {@code paid}; a retry that exhausts the bound marks it {@code failed}. A
 * success also triggers the Invoice's receipt rendering and writes a {@code
 * PAYMENT_SUCCEEDED} {@link OutboxEvent}, both in the same transaction as the {@code
 * paid} transition, so a receipt and a notification exist for every paid Invoice with no
 * separate trigger required — this is the single point every successful-charge path
 * (renewal, Dunning recovery, self-service recovery) funnels through, so the event fires
 * exactly once per successful charge regardless of which path produced it.
 */
@Component
public class ChargeRecordingAdapter implements ChargeRecordingPort {

    private static final Logger log = LoggerFactory.getLogger(ChargeRecordingAdapter.class);

    private final InvoiceRepository invoiceRepository;
    private final PaymentAttemptRepository paymentAttemptRepository;
    private final ReceiptRenderingService receiptRenderingService;
    private final OutboxEventRepository outboxEventRepository;

    public ChargeRecordingAdapter(InvoiceRepository invoiceRepository, PaymentAttemptRepository paymentAttemptRepository,
                                   ReceiptRenderingService receiptRenderingService, OutboxEventRepository outboxEventRepository) {
        this.invoiceRepository = invoiceRepository;
        this.paymentAttemptRepository = paymentAttemptRepository;
        this.receiptRenderingService = receiptRenderingService;
        this.outboxEventRepository = outboxEventRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean invoiceAlreadyRecorded(UUID subscriptionId, LocalDate billingPeriod) {
        return invoiceRepository.existsBySubscriptionIdAndBillingPeriod(subscriptionId, billingPeriod);
    }

    @Override
    @Transactional
    public void recordSuccessfulCharge(UUID subscriptionId, LocalDate billingPeriod, UUID priceVersionId,
                                        String gatewayTransactionId, Instant attemptedAt) {
        Invoice invoice = findOrCreateInvoice(subscriptionId, billingPeriod, priceVersionId);
        paymentAttemptRepository.save(
                new PaymentAttempt(UUID.randomUUID(), invoice, PaymentAttemptStatus.SUCCEEDED, attemptedAt));
        invoice.markPaid();
        invoiceRepository.save(invoice);
        receiptRenderingService.renderAndStore(invoice, attemptedAt);
        outboxEventRepository.save(new OutboxEvent(UUID.randomUUID(), "PAYMENT_SUCCEEDED",
                receiptPayload(invoice.getId(), subscriptionId, billingPeriod)));
        log.info("Charge succeeded for subscription {} billing period {}: invoice {}, gateway transaction {}",
                subscriptionId, billingPeriod, invoice.getId(), gatewayTransactionId);
    }

    @Override
    @Transactional
    public UUID recordFailedCharge(UUID subscriptionId, LocalDate billingPeriod, UUID priceVersionId,
                                    Instant attemptedAt) {
        Invoice invoice = findOrCreateInvoice(subscriptionId, billingPeriod, priceVersionId);
        paymentAttemptRepository.save(
                new PaymentAttempt(UUID.randomUUID(), invoice, PaymentAttemptStatus.FAILED, attemptedAt));
        log.info("Charge declined for subscription {} billing period {}: invoice {}",
                subscriptionId, billingPeriod, invoice.getId());
        return invoice.getId();
    }

    @Override
    @Transactional
    public DunningRetryState recordRetryAttempt(UUID invoiceId) {
        Invoice invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new IllegalStateException("Invoice " + invoiceId + " does not exist"));
        invoice.recordRetryAttempt();
        if (invoice.retriesExhausted()) {
            invoice.markFailed();
        }
        invoiceRepository.saveAndFlush(invoice);
        return new DunningRetryState(invoice.getCreatedAt(), invoice.getRetriesUsed(), invoice.retriesExhausted());
    }

    private static String receiptPayload(UUID invoiceId, UUID subscriptionId, LocalDate billingPeriod) {
        return "{\"invoiceId\":\"" + invoiceId + "\",\"subscriptionId\":\"" + subscriptionId
                + "\",\"billingPeriod\":\"" + billingPeriod + "\"}";
    }

    private Invoice findOrCreateInvoice(UUID subscriptionId, LocalDate billingPeriod, UUID priceVersionId) {
        return invoiceRepository.findBySubscriptionIdAndBillingPeriod(subscriptionId, billingPeriod)
                .orElseGet(() -> createInvoice(subscriptionId, billingPeriod, priceVersionId));
    }

    private Invoice createInvoice(UUID subscriptionId, LocalDate billingPeriod, UUID priceVersionId) {
        try {
            // flush now, not at transaction commit, so a losing concurrent insert's
            // constraint violation surfaces here -- inside this method's own transaction
            // -- rather than propagating from an unrelated later commit.
            return invoiceRepository.saveAndFlush(new Invoice(UUID.randomUUID(), subscriptionId, billingPeriod, priceVersionId));
        } catch (DataIntegrityViolationException lostRace) {
            log.info("Lost the race creating the Invoice for subscription {} billing period {}: already recorded by another run",
                    subscriptionId, billingPeriod);
            throw new ChargeAlreadyRecordedException(
                    "Invoice for subscription " + subscriptionId + " billing period " + billingPeriod
                            + " was already created by another run", lostRace);
        }
    }
}
