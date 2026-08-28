package com.subscriptionbilling.invoicing.receipt;

import com.subscriptionbilling.invoicing.invoice.Invoice;
import com.subscriptionbilling.invoicing.invoice.InvoiceAccessDeniedException;
import com.subscriptionbilling.invoicing.invoice.InvoiceRepository;
import com.subscriptionbilling.invoicing.invoice.SubscriptionOwnershipPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Unit coverage of {@link ReceiptService}: ownership is checked before any Receipt data
 * is returned (an unowned or nonexistent target Invoice collapses to the same {@link
 * InvoiceAccessDeniedException} {@code InvoiceService} throws), and a download is
 * rejected with {@link ReceiptNotAvailableException} for any status other than
 * {@code paid}.
 */
@ExtendWith(MockitoExtension.class)
class ReceiptServiceTest {

    @Mock
    private InvoiceRepository invoiceRepository;

    @Mock
    private SubscriptionOwnershipPort subscriptionOwnershipPort;

    @Mock
    private ReceiptRepository receiptRepository;

    private final UUID customerId = UUID.randomUUID();
    private final UUID subscriptionId = UUID.randomUUID();

    private ReceiptService service() {
        return new ReceiptService(invoiceRepository, subscriptionOwnershipPort, receiptRepository);
    }

    @Test
    void getPdfRejectsANonexistentInvoice() {
        UUID invoiceId = UUID.randomUUID();
        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().getPdf(invoiceId, customerId))
                .isInstanceOf(InvoiceAccessDeniedException.class);
    }

    @Test
    void getPdfRejectsAnInvoiceWhoseSubscriptionBelongsToADifferentCustomer() {
        UUID invoiceId = UUID.randomUUID();
        Invoice invoice = new Invoice(invoiceId, subscriptionId, LocalDate.of(2026, 7, 31), UUID.randomUUID());
        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.of(invoice));
        when(subscriptionOwnershipPort.isOwnedByCustomer(subscriptionId, customerId)).thenReturn(false);

        assertThatThrownBy(() -> service().getPdf(invoiceId, customerId))
                .isInstanceOf(InvoiceAccessDeniedException.class);
    }

    @Test
    void getPdfRejectsAnOpenInvoiceWithReceiptNotAvailable() {
        UUID invoiceId = UUID.randomUUID();
        Invoice invoice = new Invoice(invoiceId, subscriptionId, LocalDate.of(2026, 7, 31), UUID.randomUUID());
        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.of(invoice));
        when(subscriptionOwnershipPort.isOwnedByCustomer(subscriptionId, customerId)).thenReturn(true);

        assertThatThrownBy(() -> service().getPdf(invoiceId, customerId))
                .isInstanceOf(ReceiptNotAvailableException.class);
    }

    @Test
    void getPdfRejectsAFailedInvoiceWithReceiptNotAvailable() {
        UUID invoiceId = UUID.randomUUID();
        Invoice invoice = new Invoice(invoiceId, subscriptionId, LocalDate.of(2026, 7, 31), UUID.randomUUID());
        invoice.markFailed();
        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.of(invoice));
        when(subscriptionOwnershipPort.isOwnedByCustomer(subscriptionId, customerId)).thenReturn(true);

        assertThatThrownBy(() -> service().getPdf(invoiceId, customerId))
                .isInstanceOf(ReceiptNotAvailableException.class);
    }

    @Test
    void getPdfReturnsTheStoredPdfForAPaidInvoice() {
        UUID invoiceId = UUID.randomUUID();
        Invoice invoice = new Invoice(invoiceId, subscriptionId, LocalDate.of(2026, 7, 31), UUID.randomUUID());
        invoice.markPaid();
        byte[] pdfBytes = "not-really-a-pdf".getBytes();
        Receipt receipt = new Receipt(UUID.randomUUID(), invoice, pdfBytes);
        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.of(invoice));
        when(subscriptionOwnershipPort.isOwnedByCustomer(subscriptionId, customerId)).thenReturn(true);
        when(receiptRepository.findByInvoiceId(invoiceId)).thenReturn(Optional.of(receipt));

        byte[] result = service().getPdf(invoiceId, customerId);

        assertThat(result).isEqualTo(pdfBytes);
    }

    @Test
    void getPdfFailsFastIfAPaidInvoiceHasNoStoredReceipt() {
        UUID invoiceId = UUID.randomUUID();
        Invoice invoice = new Invoice(invoiceId, subscriptionId, LocalDate.of(2026, 7, 31), UUID.randomUUID());
        invoice.markPaid();
        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.of(invoice));
        when(subscriptionOwnershipPort.isOwnedByCustomer(subscriptionId, customerId)).thenReturn(true);
        when(receiptRepository.findByInvoiceId(invoiceId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().getPdf(invoiceId, customerId))
                .isInstanceOf(IllegalStateException.class);
    }
}
