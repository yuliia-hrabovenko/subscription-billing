package com.subscriptionbilling.invoicing.invoice;

import com.subscriptionbilling.billingjob.invoicing.ChargeAlreadyRecordedException;
import com.subscriptionbilling.billingjob.invoicing.DunningRetryState;
import com.subscriptionbilling.billingjob.invoicing.RecordedChargeOutcome;
import com.subscriptionbilling.invoicing.receipt.ReceiptRenderingService;
import com.subscriptionbilling.notifications.outbox.OutboxEvent;
import com.subscriptionbilling.notifications.outbox.OutboxEventRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit coverage of {@link ChargeRecordingAdapter}: it creates an Invoice only on the
 * first charge attempt for a Billing Cycle — success or failure — attaches every
 * subsequent one to the existing row, appends a PaymentAttempt with the outcome's status,
 * and translates a concurrent-create constraint violation into {@link
 * ChargeAlreadyRecordedException} rather than letting it surface raw or creating a
 * duplicate PaymentAttempt. Also covers the Invoice status transitions it drives: {@code
 * paid} on a successful charge, and {@code failed} once a recorded retry exhausts the
 * bound. Whether the {@code (subscription_id, billing_period)} uniqueness constraint
 * itself holds is covered by {@link InvoiceRepositoryTest}.
 */
@ExtendWith(MockitoExtension.class)
class ChargeRecordingAdapterTest {

    @Mock
    private InvoiceRepository invoiceRepository;

    @Mock
    private PaymentAttemptRepository paymentAttemptRepository;

    @Mock
    private ReceiptRenderingService receiptRenderingService;

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Test
    void createsANewInvoiceAndASucceededPaymentAttemptWhenNoInvoiceExistsYetForTheCycle() {
        UUID subscriptionId = UUID.randomUUID();
        LocalDate billingPeriod = LocalDate.of(2026, 7, 31);
        UUID priceVersionId = UUID.randomUUID();
        Instant attemptedAt = Instant.parse("2026-08-24T03:00:00Z");
        when(invoiceRepository.findBySubscriptionIdAndBillingPeriod(subscriptionId, billingPeriod))
                .thenReturn(Optional.empty());
        when(invoiceRepository.saveAndFlush(any(Invoice.class))).thenAnswer(invocation -> invocation.getArgument(0));

        new ChargeRecordingAdapter(invoiceRepository, paymentAttemptRepository, receiptRenderingService, outboxEventRepository)
                .recordSuccessfulCharge(subscriptionId, billingPeriod, priceVersionId, "gw-txn-1", attemptedAt);

        ArgumentCaptor<Invoice> invoiceCaptor = ArgumentCaptor.forClass(Invoice.class);
        verify(invoiceRepository).saveAndFlush(invoiceCaptor.capture());
        Invoice createdInvoice = invoiceCaptor.getValue();
        assertThat(createdInvoice.getSubscriptionId()).isEqualTo(subscriptionId);
        assertThat(createdInvoice.getBillingPeriod()).isEqualTo(billingPeriod);
        assertThat(createdInvoice.getPriceVersionId()).isEqualTo(priceVersionId);
        assertThat(createdInvoice.getStatus()).isEqualTo(InvoiceStatus.PAID);
        verify(invoiceRepository).save(createdInvoice);

        ArgumentCaptor<PaymentAttempt> attemptCaptor = ArgumentCaptor.forClass(PaymentAttempt.class);
        verify(paymentAttemptRepository).save(attemptCaptor.capture());
        PaymentAttempt createdAttempt = attemptCaptor.getValue();
        assertThat(createdAttempt.getStatus()).isEqualTo(PaymentAttemptStatus.SUCCEEDED);
        assertThat(createdAttempt.getAttemptedAt()).isEqualTo(attemptedAt);
        assertThat(createdAttempt.getInvoice()).isEqualTo(createdInvoice);
        assertThat(createdAttempt.getGatewayReference()).isEqualTo("gw-txn-1");
        verify(receiptRenderingService).renderAndStore(createdInvoice, attemptedAt);

        ArgumentCaptor<OutboxEvent> outboxEventCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(outboxEventCaptor.capture());
        OutboxEvent outboxEvent = outboxEventCaptor.getValue();
        assertThat(outboxEvent.getEventType()).isEqualTo("PAYMENT_SUCCEEDED");
        assertThat(outboxEvent.getPayload())
                .contains(createdInvoice.getId().toString())
                .contains(subscriptionId.toString())
                .contains(billingPeriod.toString());
    }

    @Test
    void attachesTheSucceededPaymentAttemptToTheExistingInvoiceWithoutCreatingASecondOne() {
        UUID subscriptionId = UUID.randomUUID();
        LocalDate billingPeriod = LocalDate.of(2026, 7, 31);
        Invoice existingInvoice = new Invoice(UUID.randomUUID(), subscriptionId, billingPeriod, UUID.randomUUID());
        Instant attemptedAt = Instant.parse("2026-08-24T03:00:00Z");
        when(invoiceRepository.findBySubscriptionIdAndBillingPeriod(subscriptionId, billingPeriod))
                .thenReturn(Optional.of(existingInvoice));

        new ChargeRecordingAdapter(invoiceRepository, paymentAttemptRepository, receiptRenderingService, outboxEventRepository)
                .recordSuccessfulCharge(subscriptionId, billingPeriod, existingInvoice.getPriceVersionId(), "gw-txn-2", attemptedAt);

        verify(invoiceRepository, never()).saveAndFlush(any());
        assertThat(existingInvoice.getStatus()).isEqualTo(InvoiceStatus.PAID);
        verify(invoiceRepository).save(existingInvoice);
        ArgumentCaptor<PaymentAttempt> attemptCaptor = ArgumentCaptor.forClass(PaymentAttempt.class);
        verify(paymentAttemptRepository).save(attemptCaptor.capture());
        assertThat(attemptCaptor.getValue().getInvoice()).isEqualTo(existingInvoice);
        assertThat(attemptCaptor.getValue().getStatus()).isEqualTo(PaymentAttemptStatus.SUCCEEDED);
        verify(receiptRenderingService).renderAndStore(existingInvoice, attemptedAt);
        verify(outboxEventRepository).save(any(OutboxEvent.class));
    }

    @Test
    void createsANewInvoiceAndAFailedPaymentAttemptWhenNoInvoiceExistsYetForTheCycle() {
        UUID subscriptionId = UUID.randomUUID();
        LocalDate billingPeriod = LocalDate.of(2026, 7, 31);
        UUID priceVersionId = UUID.randomUUID();
        Instant attemptedAt = Instant.parse("2026-08-24T03:00:00Z");
        when(invoiceRepository.findBySubscriptionIdAndBillingPeriod(subscriptionId, billingPeriod))
                .thenReturn(Optional.empty());
        when(invoiceRepository.saveAndFlush(any(Invoice.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UUID invoiceId = new ChargeRecordingAdapter(invoiceRepository, paymentAttemptRepository, receiptRenderingService, outboxEventRepository)
                .recordFailedCharge(subscriptionId, billingPeriod, priceVersionId, "gw-declined-1", attemptedAt);

        ArgumentCaptor<Invoice> invoiceCaptor = ArgumentCaptor.forClass(Invoice.class);
        verify(invoiceRepository).saveAndFlush(invoiceCaptor.capture());
        Invoice createdInvoice = invoiceCaptor.getValue();
        assertThat(invoiceId).isEqualTo(createdInvoice.getId());
        // Not yet terminal: a first failed attempt still awaits its Dunning retries.
        assertThat(createdInvoice.getStatus()).isEqualTo(InvoiceStatus.OPEN);

        ArgumentCaptor<PaymentAttempt> attemptCaptor = ArgumentCaptor.forClass(PaymentAttempt.class);
        verify(paymentAttemptRepository).save(attemptCaptor.capture());
        assertThat(attemptCaptor.getValue().getStatus()).isEqualTo(PaymentAttemptStatus.FAILED);
        assertThat(attemptCaptor.getValue().getInvoice()).isEqualTo(createdInvoice);
        assertThat(attemptCaptor.getValue().getGatewayReference()).isEqualTo("gw-declined-1");
        verifyNoInteractions(receiptRenderingService);
        verifyNoInteractions(outboxEventRepository);
    }

    @Test
    void attachesTheFailedPaymentAttemptToTheExistingInvoiceWithoutCreatingASecondOne() {
        UUID subscriptionId = UUID.randomUUID();
        LocalDate billingPeriod = LocalDate.of(2026, 7, 31);
        Invoice existingInvoice = new Invoice(UUID.randomUUID(), subscriptionId, billingPeriod, UUID.randomUUID());
        Instant attemptedAt = Instant.parse("2026-08-24T03:00:00Z");
        when(invoiceRepository.findBySubscriptionIdAndBillingPeriod(subscriptionId, billingPeriod))
                .thenReturn(Optional.of(existingInvoice));

        UUID invoiceId = new ChargeRecordingAdapter(invoiceRepository, paymentAttemptRepository, receiptRenderingService, outboxEventRepository)
                .recordFailedCharge(subscriptionId, billingPeriod, existingInvoice.getPriceVersionId(), null, attemptedAt);

        assertThat(invoiceId).isEqualTo(existingInvoice.getId());
        verify(invoiceRepository, never()).saveAndFlush(any());
        assertThat(existingInvoice.getStatus()).isEqualTo(InvoiceStatus.OPEN);
        ArgumentCaptor<PaymentAttempt> attemptCaptor = ArgumentCaptor.forClass(PaymentAttempt.class);
        verify(paymentAttemptRepository).save(attemptCaptor.capture());
        assertThat(attemptCaptor.getValue().getInvoice()).isEqualTo(existingInvoice);
        assertThat(attemptCaptor.getValue().getStatus()).isEqualTo(PaymentAttemptStatus.FAILED);
        verifyNoInteractions(receiptRenderingService);
        verifyNoInteractions(outboxEventRepository);
    }

    @Test
    void recordingARetryAttemptThatDoesNotExhaustTheBoundLeavesTheInvoiceOpen() {
        UUID invoiceId = UUID.randomUUID();
        Invoice invoice = new Invoice(invoiceId, UUID.randomUUID(), LocalDate.of(2026, 7, 31), UUID.randomUUID());
        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.of(invoice));

        DunningRetryState state = new ChargeRecordingAdapter(invoiceRepository, paymentAttemptRepository, receiptRenderingService, outboxEventRepository)
                .recordRetryAttempt(invoiceId);

        assertThat(state.retriesUsed()).isEqualTo(1);
        assertThat(state.retriesExhausted()).isFalse();
        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.OPEN);
        verify(invoiceRepository).saveAndFlush(invoice);
        verifyNoInteractions(receiptRenderingService);
        verifyNoInteractions(outboxEventRepository);
    }

    @Test
    void recordingTheRetryAttemptThatExhaustsTheBoundMarksTheInvoiceFailed() {
        UUID invoiceId = UUID.randomUUID();
        Invoice invoice = new Invoice(invoiceId, UUID.randomUUID(), LocalDate.of(2026, 7, 31), UUID.randomUUID());
        invoice.recordRetryAttempt();
        invoice.recordRetryAttempt();
        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.of(invoice));

        DunningRetryState state = new ChargeRecordingAdapter(invoiceRepository, paymentAttemptRepository, receiptRenderingService, outboxEventRepository)
                .recordRetryAttempt(invoiceId);

        assertThat(state.retriesUsed()).isEqualTo(3);
        assertThat(state.retriesExhausted()).isTrue();
        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.FAILED);
        verify(invoiceRepository).saveAndFlush(invoice);
        verifyNoInteractions(receiptRenderingService);
        verifyNoInteractions(outboxEventRepository);
    }

    @Test
    void invoiceAlreadyRecordedDelegatesToTheRepositoryExistenceCheck() {
        UUID subscriptionId = UUID.randomUUID();
        LocalDate billingPeriod = LocalDate.of(2026, 7, 31);
        when(invoiceRepository.existsBySubscriptionIdAndBillingPeriod(subscriptionId, billingPeriod)).thenReturn(true);

        boolean alreadyRecorded = new ChargeRecordingAdapter(invoiceRepository, paymentAttemptRepository, receiptRenderingService, outboxEventRepository)
                .invoiceAlreadyRecorded(subscriptionId, billingPeriod);

        assertThat(alreadyRecorded).isTrue();
    }

    @Test
    void losingTheConcurrentCreateInvoiceRaceOnASuccessfulChargeThrowsWithoutRecordingAPaymentAttempt() {
        UUID subscriptionId = UUID.randomUUID();
        LocalDate billingPeriod = LocalDate.of(2026, 7, 31);
        Instant attemptedAt = Instant.parse("2026-08-24T03:00:00Z");
        when(invoiceRepository.findBySubscriptionIdAndBillingPeriod(subscriptionId, billingPeriod))
                .thenReturn(Optional.empty());
        when(invoiceRepository.saveAndFlush(any(Invoice.class)))
                .thenThrow(new DataIntegrityViolationException("uq_invoice_subscription_billing_period"));

        ChargeRecordingAdapter adapter = new ChargeRecordingAdapter(invoiceRepository, paymentAttemptRepository, receiptRenderingService, outboxEventRepository);

        assertThatThrownBy(() -> adapter.recordSuccessfulCharge(
                subscriptionId, billingPeriod, UUID.randomUUID(), "gw-txn-1", attemptedAt))
                .isInstanceOf(ChargeAlreadyRecordedException.class);
        verify(paymentAttemptRepository, never()).save(any());
        verifyNoInteractions(outboxEventRepository);
    }

    @Test
    void losingTheConcurrentCreateInvoiceRaceOnAFailedChargeThrowsWithoutRecordingAPaymentAttempt() {
        UUID subscriptionId = UUID.randomUUID();
        LocalDate billingPeriod = LocalDate.of(2026, 7, 31);
        Instant attemptedAt = Instant.parse("2026-08-24T03:00:00Z");
        when(invoiceRepository.findBySubscriptionIdAndBillingPeriod(subscriptionId, billingPeriod))
                .thenReturn(Optional.empty());
        when(invoiceRepository.saveAndFlush(any(Invoice.class)))
                .thenThrow(new DataIntegrityViolationException("uq_invoice_subscription_billing_period"));

        ChargeRecordingAdapter adapter = new ChargeRecordingAdapter(invoiceRepository, paymentAttemptRepository, receiptRenderingService, outboxEventRepository);

        assertThatThrownBy(() -> adapter.recordFailedCharge(subscriptionId, billingPeriod, UUID.randomUUID(), null, attemptedAt))
                .isInstanceOf(ChargeAlreadyRecordedException.class);
        verify(paymentAttemptRepository, never()).save(any());
        verifyNoInteractions(outboxEventRepository);
    }

    @Test
    void findRecordedOutcomeReturnsEmptyWhenNoPaymentAttemptCarriesTheGatewayReference() {
        when(paymentAttemptRepository.findFirstByGatewayReference("gw-unknown")).thenReturn(Optional.empty());

        Optional<RecordedChargeOutcome> outcome = new ChargeRecordingAdapter(
                invoiceRepository, paymentAttemptRepository, receiptRenderingService, outboxEventRepository)
                .findRecordedOutcome("gw-unknown");

        assertThat(outcome).isEmpty();
    }

    @Test
    void findRecordedOutcomeMapsASucceededPaymentAttemptToTheSucceededOutcome() {
        Invoice invoice = new Invoice(UUID.randomUUID(), UUID.randomUUID(), LocalDate.of(2026, 7, 31), UUID.randomUUID());
        PaymentAttempt attempt = new PaymentAttempt(
                UUID.randomUUID(), invoice, PaymentAttemptStatus.SUCCEEDED, Instant.now(), "gw-txn-1");
        when(paymentAttemptRepository.findFirstByGatewayReference("gw-txn-1")).thenReturn(Optional.of(attempt));

        Optional<RecordedChargeOutcome> outcome = new ChargeRecordingAdapter(
                invoiceRepository, paymentAttemptRepository, receiptRenderingService, outboxEventRepository)
                .findRecordedOutcome("gw-txn-1");

        assertThat(outcome).contains(RecordedChargeOutcome.SUCCEEDED);
    }

    @Test
    void findRecordedOutcomeMapsAFailedPaymentAttemptToTheFailedOutcome() {
        Invoice invoice = new Invoice(UUID.randomUUID(), UUID.randomUUID(), LocalDate.of(2026, 7, 31), UUID.randomUUID());
        PaymentAttempt attempt = new PaymentAttempt(
                UUID.randomUUID(), invoice, PaymentAttemptStatus.FAILED, Instant.now(), "gw-declined-1");
        when(paymentAttemptRepository.findFirstByGatewayReference("gw-declined-1")).thenReturn(Optional.of(attempt));

        Optional<RecordedChargeOutcome> outcome = new ChargeRecordingAdapter(
                invoiceRepository, paymentAttemptRepository, receiptRenderingService, outboxEventRepository)
                .findRecordedOutcome("gw-declined-1");

        assertThat(outcome).contains(RecordedChargeOutcome.FAILED);
    }
}
