package com.subscriptionbilling.invoicing.receipt;

import com.subscriptionbilling.billingjob.invoicing.PriceVersionSnapshot;
import com.subscriptionbilling.billingjob.invoicing.PriceVersionSnapshotPort;
import com.subscriptionbilling.invoicing.invoice.Invoice;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit coverage of {@link ReceiptRenderingService}: it describes the Invoice's own
 * snapshotted PriceVersion id -- never the Plan's current price (Invariant 9) -- renders
 * the PDF from that description, and stores the result against the Invoice.
 */
@ExtendWith(MockitoExtension.class)
class ReceiptRenderingServiceTest {

    @Mock
    private PriceVersionSnapshotPort priceVersionSnapshotPort;

    @Mock
    private ReceiptRenderer receiptRenderer;

    @Mock
    private ReceiptRepository receiptRepository;

    @Test
    void rendersAndStoresAReceiptUsingTheInvoicesOwnSnapshottedPriceVersion() {
        UUID priceVersionId = UUID.randomUUID();
        Invoice invoice = new Invoice(UUID.randomUUID(), UUID.randomUUID(), LocalDate.of(2026, 7, 31), priceVersionId);
        Instant chargedAt = Instant.parse("2026-08-24T03:00:00Z");
        PriceVersionSnapshot snapshot = new PriceVersionSnapshot("Enterprise", new BigDecimal("49.00"));
        byte[] pdf = {1, 2, 3};
        when(priceVersionSnapshotPort.describe(priceVersionId)).thenReturn(snapshot);
        when(receiptRenderer.render(invoice.getId(), snapshot.planName(), snapshot.amount(), chargedAt)).thenReturn(pdf);

        new ReceiptRenderingService(priceVersionSnapshotPort, receiptRenderer, receiptRepository)
                .renderAndStore(invoice, chargedAt);

        ArgumentCaptor<Receipt> receiptCaptor = ArgumentCaptor.forClass(Receipt.class);
        verify(receiptRepository).save(receiptCaptor.capture());
        Receipt savedReceipt = receiptCaptor.getValue();
        assertThat(savedReceipt.getInvoice()).isEqualTo(invoice);
        assertThat(savedReceipt.getPdf()).isEqualTo(pdf);
    }
}
