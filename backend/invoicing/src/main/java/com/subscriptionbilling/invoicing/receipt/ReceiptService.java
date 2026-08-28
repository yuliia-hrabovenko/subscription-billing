package com.subscriptionbilling.invoicing.receipt;

import com.subscriptionbilling.invoicing.invoice.Invoice;
import com.subscriptionbilling.invoicing.invoice.InvoiceAccessDeniedException;
import com.subscriptionbilling.invoicing.invoice.InvoiceRepository;
import com.subscriptionbilling.invoicing.invoice.InvoiceStatus;
import com.subscriptionbilling.invoicing.invoice.SubscriptionOwnershipPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Read-side use case behind the receipt download endpoint: resolves ownership through
 * the Invoice's Subscription (an Invoice has no {@code customerId} of its own, per
 * ADR-0003 — the same rule {@code InvoiceService} applies to list/get) before returning
 * anything, and rejects a download for an Invoice that hasn't reached {@code paid}.
 */
@Service
public class ReceiptService {

    private final InvoiceRepository invoiceRepository;
    private final SubscriptionOwnershipPort subscriptionOwnershipPort;
    private final ReceiptRepository receiptRepository;

    public ReceiptService(InvoiceRepository invoiceRepository, SubscriptionOwnershipPort subscriptionOwnershipPort,
                           ReceiptRepository receiptRepository) {
        this.invoiceRepository = invoiceRepository;
        this.subscriptionOwnershipPort = subscriptionOwnershipPort;
        this.receiptRepository = receiptRepository;
    }

    /**
     * @param invoiceId               the Invoice to download the receipt for
     * @param authenticatedCustomerId the Customer the caller's bearer token identifies
     * @return the rendered PDF's bytes
     * @throws InvoiceAccessDeniedException if the Invoice doesn't exist or its
     *         Subscription doesn't belong to this Customer
     * @throws ReceiptNotAvailableException if the Invoice hasn't reached {@code paid}
     */
    @Transactional(readOnly = true)
    public byte[] getPdf(UUID invoiceId, UUID authenticatedCustomerId) {
        Invoice invoice = invoiceRepository.findById(invoiceId)
                .filter(candidate -> subscriptionOwnershipPort.isOwnedByCustomer(candidate.getSubscriptionId(), authenticatedCustomerId))
                .orElseThrow(() -> new InvoiceAccessDeniedException(invoiceId));
        if (invoice.getStatus() != InvoiceStatus.PAID) {
            throw new ReceiptNotAvailableException(invoiceId, invoice.getStatus());
        }
        return receiptRepository.findByInvoiceId(invoiceId)
                .orElseThrow(() -> new IllegalStateException("Paid invoice " + invoiceId + " has no stored receipt"))
                .getPdf();
    }
}
