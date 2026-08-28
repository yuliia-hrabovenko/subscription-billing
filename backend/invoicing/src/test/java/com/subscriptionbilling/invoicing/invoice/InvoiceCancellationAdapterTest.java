package com.subscriptionbilling.invoicing.invoice;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit coverage of {@link InvoiceCancellationAdapter}: it resolves the still-open
 * Invoice for the given cycle and marks it {@code failed}, or fails fast if no such
 * Invoice exists.
 */
@ExtendWith(MockitoExtension.class)
class InvoiceCancellationAdapterTest {

    @Mock
    private InvoiceRepository invoiceRepository;

    @Test
    void failStillOpenInvoiceMarksTheMatchingInvoiceFailedAndPersistsIt() {
        UUID subscriptionId = UUID.randomUUID();
        LocalDate billingPeriod = LocalDate.of(2026, 8, 1);
        Invoice invoice = new Invoice(UUID.randomUUID(), subscriptionId, billingPeriod, UUID.randomUUID());
        when(invoiceRepository.findBySubscriptionIdAndBillingPeriod(subscriptionId, billingPeriod))
                .thenReturn(Optional.of(invoice));

        new InvoiceCancellationAdapter(invoiceRepository).failStillOpenInvoice(subscriptionId, billingPeriod);

        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.FAILED);
        verify(invoiceRepository).save(invoice);
    }

    @Test
    void failStillOpenInvoiceFailsFastWhenNoInvoiceExistsForTheCycle() {
        UUID subscriptionId = UUID.randomUUID();
        LocalDate billingPeriod = LocalDate.of(2026, 8, 1);
        when(invoiceRepository.findBySubscriptionIdAndBillingPeriod(subscriptionId, billingPeriod))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> new InvoiceCancellationAdapter(invoiceRepository)
                .failStillOpenInvoice(subscriptionId, billingPeriod))
                .isInstanceOf(IllegalStateException.class);
    }
}
