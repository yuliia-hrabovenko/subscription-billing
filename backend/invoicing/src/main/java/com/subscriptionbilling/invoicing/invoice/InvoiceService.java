package com.subscriptionbilling.invoicing.invoice;

import com.subscriptionbilling.billingjob.invoicing.PriceVersionSnapshot;
import com.subscriptionbilling.billingjob.invoicing.PriceVersionSnapshotPort;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Read-side use cases behind the Invoice list/get endpoints: resolves ownership (an
 * Invoice has no {@code customerId} of its own — ownership resolves through its
 * Subscription) before returning anything, and always resolves the
 * charged amount from the Invoice's snapshotted PriceVersion, never "the Plan's current
 * price" (Invariant 9).
 */
@Service
public class InvoiceService {

    private final InvoiceRepository invoiceRepository;
    private final PaymentAttemptRepository paymentAttemptRepository;
    private final SubscriptionOwnershipPort subscriptionOwnershipPort;
    private final PriceVersionSnapshotPort priceVersionSnapshotPort;

    public InvoiceService(InvoiceRepository invoiceRepository, PaymentAttemptRepository paymentAttemptRepository,
                           SubscriptionOwnershipPort subscriptionOwnershipPort, PriceVersionSnapshotPort priceVersionSnapshotPort) {
        this.invoiceRepository = invoiceRepository;
        this.paymentAttemptRepository = paymentAttemptRepository;
        this.subscriptionOwnershipPort = subscriptionOwnershipPort;
        this.priceVersionSnapshotPort = priceVersionSnapshotPort;
    }

    /**
     * @param subscriptionId          the Subscription to list Invoices for
     * @param authenticatedCustomerId the Customer the caller's bearer token identifies
     * @param cursor                  the previous page's {@link InvoicePage#nextCursor()},
     *                                or null/blank to fetch the first page
     * @param pageSize                the maximum number of Invoices to return
     * @return this Subscription's Invoices, newest first
     * @throws InvoiceAccessDeniedException if the Subscription doesn't exist or doesn't
     *         belong to this Customer
     * @throws InvalidCursorException       if {@code cursor} is non-blank but not one
     *         this API issued
     */
    @Transactional(readOnly = true)
    public InvoicePage listForSubscription(UUID subscriptionId, UUID authenticatedCustomerId, String cursor, int pageSize) {
        if (!subscriptionOwnershipPort.isOwnedByCustomer(subscriptionId, authenticatedCustomerId)) {
            throw new InvoiceAccessDeniedException(subscriptionId);
        }
        InvoiceCursor.Position position = InvoiceCursor.decode(cursor);

        List<Invoice> rows = invoiceRepository.findPageBySubscriptionId(
                subscriptionId,
                position != null ? position.createdAt() : null,
                position != null ? position.id() : null,
                PageRequest.of(0, pageSize + 1));

        boolean hasNextPage = rows.size() > pageSize;
        List<Invoice> page = hasNextPage ? rows.subList(0, pageSize) : rows;
        String nextCursor = hasNextPage
                ? InvoiceCursor.encode(page.get(page.size() - 1).getCreatedAt(), page.get(page.size() - 1).getId())
                : null;

        List<InvoiceSummary> items = page.stream()
                .map(invoice -> InvoiceSummary.from(invoice, priceVersionSnapshotPort.describe(invoice.getPriceVersionId())))
                .toList();
        return new InvoicePage(items, nextCursor);
    }

    /**
     * @param invoiceId               the Invoice to fetch
     * @param authenticatedCustomerId the Customer the caller's bearer token identifies
     * @return the Invoice together with its full PaymentAttempt history, oldest first
     * @throws InvoiceAccessDeniedException if the Invoice doesn't exist or its
     *         Subscription doesn't belong to this Customer
     */
    @Transactional(readOnly = true)
    public InvoiceDetail getById(UUID invoiceId, UUID authenticatedCustomerId) {
        Invoice invoice = invoiceRepository.findById(invoiceId)
                .filter(candidate -> subscriptionOwnershipPort.isOwnedByCustomer(candidate.getSubscriptionId(), authenticatedCustomerId))
                .orElseThrow(() -> new InvoiceAccessDeniedException(invoiceId));

        PriceVersionSnapshot priceVersionSnapshot = priceVersionSnapshotPort.describe(invoice.getPriceVersionId());
        List<PaymentAttempt> attempts = paymentAttemptRepository.findByInvoiceId(invoiceId);
        return InvoiceDetail.from(invoice, priceVersionSnapshot, attempts);
    }

    /**
     * Admin-facing counterpart to {@link #listForSubscription}: same
     * cursor-paginated query, no ownership check.
     *
     * @throws InvalidCursorException if {@code cursor} is non-blank but not one this API
     *         issued
     */
    @Transactional(readOnly = true)
    public InvoicePage listForSubscriptionAsAdmin(UUID subscriptionId, String cursor, int pageSize) {
        InvoiceCursor.Position position = InvoiceCursor.decode(cursor);

        List<Invoice> rows = invoiceRepository.findPageBySubscriptionId(
                subscriptionId,
                position != null ? position.createdAt() : null,
                position != null ? position.id() : null,
                PageRequest.of(0, pageSize + 1));

        boolean hasNextPage = rows.size() > pageSize;
        List<Invoice> page = hasNextPage ? rows.subList(0, pageSize) : rows;
        String nextCursor = hasNextPage
                ? InvoiceCursor.encode(page.get(page.size() - 1).getCreatedAt(), page.get(page.size() - 1).getId())
                : null;

        List<InvoiceSummary> items = page.stream()
                .map(invoice -> InvoiceSummary.from(invoice, priceVersionSnapshotPort.describe(invoice.getPriceVersionId())))
                .toList();
        return new InvoicePage(items, nextCursor);
    }

    /**
     * Admin-facing counterpart to {@link #getById}: no ownership check.
     *
     * @throws InvoiceNotFoundException if {@code invoiceId} doesn't exist
     */
    @Transactional(readOnly = true)
    public InvoiceDetail getByIdAsAdmin(UUID invoiceId) {
        Invoice invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new InvoiceNotFoundException(invoiceId));

        PriceVersionSnapshot priceVersionSnapshot = priceVersionSnapshotPort.describe(invoice.getPriceVersionId());
        List<PaymentAttempt> attempts = paymentAttemptRepository.findByInvoiceId(invoiceId);
        return InvoiceDetail.from(invoice, priceVersionSnapshot, attempts);
    }
}
