package com.subscriptionbilling.invoicing.receipt;

import com.subscriptionbilling.billingjob.invoicing.PriceVersionSnapshot;
import com.subscriptionbilling.billingjob.invoicing.PriceVersionSnapshotPort;
import com.subscriptionbilling.invoicing.invoice.Invoice;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Renders and stores the PDF receipt for an Invoice's transition to {@code paid}. The
 * charged amount and Plan name come from {@link PriceVersionSnapshotPort}, resolved by
 * the Invoice's own snapshotted PriceVersion id rather than the Plan's current price
 * (Invariant 9).
 */
@Component
public class ReceiptRenderingService {

    private final PriceVersionSnapshotPort priceVersionSnapshotPort;
    private final ReceiptRenderer receiptRenderer;
    private final ReceiptRepository receiptRepository;

    public ReceiptRenderingService(PriceVersionSnapshotPort priceVersionSnapshotPort, ReceiptRenderer receiptRenderer,
                                    ReceiptRepository receiptRepository) {
        this.priceVersionSnapshotPort = priceVersionSnapshotPort;
        this.receiptRenderer = receiptRenderer;
        this.receiptRepository = receiptRepository;
    }

    /**
     * Renders {@code invoice}'s receipt PDF and stores it, ready for later download
     * without re-rendering.
     *
     * @param invoice   the just-paid Invoice to render a receipt for
     * @param chargedAt the instant the successful Payment Attempt resolved
     */
    public void renderAndStore(Invoice invoice, Instant chargedAt) {
        PriceVersionSnapshot priceVersionSnapshot = priceVersionSnapshotPort.describe(invoice.getPriceVersionId());
        byte[] pdf = receiptRenderer.render(invoice.getId(), priceVersionSnapshot.planName(), priceVersionSnapshot.amount(), chargedAt);
        receiptRepository.save(new Receipt(UUID.randomUUID(), invoice, pdf));
    }
}
