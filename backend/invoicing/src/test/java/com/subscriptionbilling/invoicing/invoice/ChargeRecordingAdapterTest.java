package com.subscriptionbilling.invoicing.invoice;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit coverage of {@link ChargeRecordingAdapter}: it creates an Invoice only on the
 * first charge attempt for a Billing Cycle — success or failure — attaches every
 * subsequent one to the existing row, and appends a PaymentAttempt with the outcome's
 * status. Whether the {@code (subscription_id, billing_period)} uniqueness constraint
 * itself holds is covered by {@link InvoiceRepositoryTest}.
 */
@ExtendWith(MockitoExtension.class)
class ChargeRecordingAdapterTest {

    @Mock
    private InvoiceRepository invoiceRepository;

    @Mock
    private PaymentAttemptRepository paymentAttemptRepository;

    @Test
    void createsANewInvoiceAndASucceededPaymentAttemptWhenNoInvoiceExistsYetForTheCycle() {
        UUID subscriptionId = UUID.randomUUID();
        LocalDate billingPeriod = LocalDate.of(2026, 7, 31);
        UUID priceVersionId = UUID.randomUUID();
        Instant attemptedAt = Instant.parse("2026-08-24T03:00:00Z");
        when(invoiceRepository.findBySubscriptionIdAndBillingPeriod(subscriptionId, billingPeriod))
                .thenReturn(Optional.empty());
        when(invoiceRepository.save(any(Invoice.class))).thenAnswer(invocation -> invocation.getArgument(0));

        new ChargeRecordingAdapter(invoiceRepository, paymentAttemptRepository)
                .recordSuccessfulCharge(subscriptionId, billingPeriod, priceVersionId, "gw-txn-1", attemptedAt);

        ArgumentCaptor<Invoice> invoiceCaptor = ArgumentCaptor.forClass(Invoice.class);
        verify(invoiceRepository).save(invoiceCaptor.capture());
        Invoice createdInvoice = invoiceCaptor.getValue();
        assertThat(createdInvoice.getSubscriptionId()).isEqualTo(subscriptionId);
        assertThat(createdInvoice.getBillingPeriod()).isEqualTo(billingPeriod);
        assertThat(createdInvoice.getPriceVersionId()).isEqualTo(priceVersionId);

        ArgumentCaptor<PaymentAttempt> attemptCaptor = ArgumentCaptor.forClass(PaymentAttempt.class);
        verify(paymentAttemptRepository).save(attemptCaptor.capture());
        PaymentAttempt createdAttempt = attemptCaptor.getValue();
        assertThat(createdAttempt.getStatus()).isEqualTo(PaymentAttemptStatus.SUCCEEDED);
        assertThat(createdAttempt.getAttemptedAt()).isEqualTo(attemptedAt);
        assertThat(createdAttempt.getInvoice()).isEqualTo(createdInvoice);
    }

    @Test
    void attachesTheSucceededPaymentAttemptToTheExistingInvoiceWithoutCreatingASecondOne() {
        UUID subscriptionId = UUID.randomUUID();
        LocalDate billingPeriod = LocalDate.of(2026, 7, 31);
        Invoice existingInvoice = new Invoice(UUID.randomUUID(), subscriptionId, billingPeriod, UUID.randomUUID());
        Instant attemptedAt = Instant.parse("2026-08-24T03:00:00Z");
        when(invoiceRepository.findBySubscriptionIdAndBillingPeriod(subscriptionId, billingPeriod))
                .thenReturn(Optional.of(existingInvoice));

        new ChargeRecordingAdapter(invoiceRepository, paymentAttemptRepository)
                .recordSuccessfulCharge(subscriptionId, billingPeriod, existingInvoice.getPriceVersionId(), "gw-txn-2", attemptedAt);

        verify(invoiceRepository, never()).save(any());
        ArgumentCaptor<PaymentAttempt> attemptCaptor = ArgumentCaptor.forClass(PaymentAttempt.class);
        verify(paymentAttemptRepository).save(attemptCaptor.capture());
        assertThat(attemptCaptor.getValue().getInvoice()).isEqualTo(existingInvoice);
        assertThat(attemptCaptor.getValue().getStatus()).isEqualTo(PaymentAttemptStatus.SUCCEEDED);
    }

    @Test
    void createsANewInvoiceAndAFailedPaymentAttemptWhenNoInvoiceExistsYetForTheCycle() {
        UUID subscriptionId = UUID.randomUUID();
        LocalDate billingPeriod = LocalDate.of(2026, 7, 31);
        UUID priceVersionId = UUID.randomUUID();
        Instant attemptedAt = Instant.parse("2026-08-24T03:00:00Z");
        when(invoiceRepository.findBySubscriptionIdAndBillingPeriod(subscriptionId, billingPeriod))
                .thenReturn(Optional.empty());
        when(invoiceRepository.save(any(Invoice.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UUID invoiceId = new ChargeRecordingAdapter(invoiceRepository, paymentAttemptRepository)
                .recordFailedCharge(subscriptionId, billingPeriod, priceVersionId, attemptedAt);

        ArgumentCaptor<Invoice> invoiceCaptor = ArgumentCaptor.forClass(Invoice.class);
        verify(invoiceRepository).save(invoiceCaptor.capture());
        Invoice createdInvoice = invoiceCaptor.getValue();
        assertThat(invoiceId).isEqualTo(createdInvoice.getId());

        ArgumentCaptor<PaymentAttempt> attemptCaptor = ArgumentCaptor.forClass(PaymentAttempt.class);
        verify(paymentAttemptRepository).save(attemptCaptor.capture());
        assertThat(attemptCaptor.getValue().getStatus()).isEqualTo(PaymentAttemptStatus.FAILED);
        assertThat(attemptCaptor.getValue().getInvoice()).isEqualTo(createdInvoice);
    }

    @Test
    void attachesTheFailedPaymentAttemptToTheExistingInvoiceWithoutCreatingASecondOne() {
        UUID subscriptionId = UUID.randomUUID();
        LocalDate billingPeriod = LocalDate.of(2026, 7, 31);
        Invoice existingInvoice = new Invoice(UUID.randomUUID(), subscriptionId, billingPeriod, UUID.randomUUID());
        Instant attemptedAt = Instant.parse("2026-08-24T03:00:00Z");
        when(invoiceRepository.findBySubscriptionIdAndBillingPeriod(subscriptionId, billingPeriod))
                .thenReturn(Optional.of(existingInvoice));

        UUID invoiceId = new ChargeRecordingAdapter(invoiceRepository, paymentAttemptRepository)
                .recordFailedCharge(subscriptionId, billingPeriod, existingInvoice.getPriceVersionId(), attemptedAt);

        assertThat(invoiceId).isEqualTo(existingInvoice.getId());
        verify(invoiceRepository, never()).save(any());
        ArgumentCaptor<PaymentAttempt> attemptCaptor = ArgumentCaptor.forClass(PaymentAttempt.class);
        verify(paymentAttemptRepository).save(attemptCaptor.capture());
        assertThat(attemptCaptor.getValue().getInvoice()).isEqualTo(existingInvoice);
        assertThat(attemptCaptor.getValue().getStatus()).isEqualTo(PaymentAttemptStatus.FAILED);
    }
}
