package com.subscriptionbilling.invoicing.invoice;

import com.subscriptionbilling.billingjob.invoicing.PriceVersionSnapshot;
import com.subscriptionbilling.billingjob.invoicing.PriceVersionSnapshotPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit coverage of {@link InvoiceService}: ownership is checked before any Invoice data
 * is returned (an unowned or nonexistent target collapses to the same {@link
 * InvoiceAccessDeniedException}), the charged amount always comes from {@link
 * PriceVersionSnapshotPort} rather than a Plan's current price (Invariant 9), and the
 * list page's {@code nextCursor} is present only when a row beyond the requested page
 * size was actually found. Whether the underlying repository query itself orders/filters
 * correctly is {@link InvoiceRepositoryTest}'s contract.
 */
@ExtendWith(MockitoExtension.class)
class InvoiceServiceTest {

    @Mock
    private InvoiceRepository invoiceRepository;

    @Mock
    private PaymentAttemptRepository paymentAttemptRepository;

    @Mock
    private SubscriptionOwnershipPort subscriptionOwnershipPort;

    @Mock
    private PriceVersionSnapshotPort priceVersionSnapshotPort;

    private final UUID customerId = UUID.randomUUID();
    private final UUID subscriptionId = UUID.randomUUID();

    private InvoiceService service() {
        return new InvoiceService(invoiceRepository, paymentAttemptRepository, subscriptionOwnershipPort, priceVersionSnapshotPort);
    }

    @Test
    void listForSubscriptionRejectsAnUnownedOrNonexistentSubscriptionBeforeQueryingInvoices() {
        when(subscriptionOwnershipPort.isOwnedByCustomer(subscriptionId, customerId)).thenReturn(false);

        assertThatThrownBy(() -> service().listForSubscription(subscriptionId, customerId, null, 20))
                .isInstanceOf(InvoiceAccessDeniedException.class);
        verify(invoiceRepository, never()).findPageBySubscriptionId(any(), any(), any(), any());
    }

    @Test
    void listForSubscriptionReturnsANullNextCursorWhenNoRowBeyondThePageSizeExists() {
        when(subscriptionOwnershipPort.isOwnedByCustomer(subscriptionId, customerId)).thenReturn(true);
        Invoice invoice = new Invoice(UUID.randomUUID(), subscriptionId, LocalDate.of(2026, 7, 31), UUID.randomUUID());
        when(invoiceRepository.findPageBySubscriptionId(eq(subscriptionId), isNull(), isNull(), any()))
                .thenReturn(List.of(invoice));
        when(priceVersionSnapshotPort.describe(invoice.getPriceVersionId()))
                .thenReturn(new PriceVersionSnapshot("Pro", new BigDecimal("19.00")));

        InvoicePage page = service().listForSubscription(subscriptionId, customerId, null, 20);

        assertThat(page.items()).singleElement().satisfies(item -> {
            assertThat(item.id()).isEqualTo(invoice.getId());
            assertThat(item.planName()).isEqualTo("Pro");
            assertThat(item.amount()).isEqualByComparingTo("19.00");
        });
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void listForSubscriptionReturnsANextCursorEncodingTheLastItemOnThePageWhenMoreRowsExist() {
        when(subscriptionOwnershipPort.isOwnedByCustomer(subscriptionId, customerId)).thenReturn(true);
        Invoice first = new Invoice(UUID.randomUUID(), subscriptionId, LocalDate.of(2026, 7, 31), UUID.randomUUID());
        Invoice second = new Invoice(UUID.randomUUID(), subscriptionId, LocalDate.of(2026, 6, 30), UUID.randomUUID());
        when(invoiceRepository.findPageBySubscriptionId(eq(subscriptionId), isNull(), isNull(), any()))
                .thenReturn(List.of(first, second));
        when(priceVersionSnapshotPort.describe(any())).thenReturn(new PriceVersionSnapshot("Pro", new BigDecimal("19.00")));

        InvoicePage page = service().listForSubscription(subscriptionId, customerId, null, 1);

        assertThat(page.items()).singleElement().extracting(InvoiceSummary::id).isEqualTo(first.getId());
        assertThat(page.nextCursor()).isEqualTo(InvoiceCursor.encode(first.getCreatedAt(), first.getId()));
    }

    @Test
    void listForSubscriptionDecodesTheGivenCursorAndPassesItsPositionToTheQuery() {
        when(subscriptionOwnershipPort.isOwnedByCustomer(subscriptionId, customerId)).thenReturn(true);
        Instant cursorCreatedAt = Instant.parse("2026-07-01T00:00:00Z");
        UUID cursorId = UUID.randomUUID();
        String cursor = InvoiceCursor.encode(cursorCreatedAt, cursorId);
        when(invoiceRepository.findPageBySubscriptionId(any(), any(), any(), any())).thenReturn(List.of());

        service().listForSubscription(subscriptionId, customerId, cursor, 20);

        verify(invoiceRepository).findPageBySubscriptionId(subscriptionId, cursorCreatedAt, cursorId,
                org.springframework.data.domain.PageRequest.of(0, 21));
    }

    @Test
    void getByIdRejectsANonexistentInvoice() {
        UUID invoiceId = UUID.randomUUID();
        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().getById(invoiceId, customerId))
                .isInstanceOf(InvoiceAccessDeniedException.class);
    }

    @Test
    void getByIdRejectsAnInvoiceWhoseSubscriptionBelongsToADifferentCustomer() {
        UUID invoiceId = UUID.randomUUID();
        Invoice invoice = new Invoice(invoiceId, subscriptionId, LocalDate.of(2026, 7, 31), UUID.randomUUID());
        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.of(invoice));
        when(subscriptionOwnershipPort.isOwnedByCustomer(subscriptionId, customerId)).thenReturn(false);

        assertThatThrownBy(() -> service().getById(invoiceId, customerId))
                .isInstanceOf(InvoiceAccessDeniedException.class);
    }

    @Test
    void getByIdReturnsTheInvoiceWithItsFullPaymentAttemptHistoryInRepositoryOrder() {
        UUID invoiceId = UUID.randomUUID();
        Invoice invoice = new Invoice(invoiceId, subscriptionId, LocalDate.of(2026, 7, 31), UUID.randomUUID());
        when(invoiceRepository.findById(invoiceId)).thenReturn(Optional.of(invoice));
        when(subscriptionOwnershipPort.isOwnedByCustomer(subscriptionId, customerId)).thenReturn(true);
        when(priceVersionSnapshotPort.describe(invoice.getPriceVersionId()))
                .thenReturn(new PriceVersionSnapshot("Pro", new BigDecimal("19.00")));
        PaymentAttempt firstAttempt = new PaymentAttempt(UUID.randomUUID(), invoice, PaymentAttemptStatus.FAILED, Instant.parse("2026-07-31T00:00:00Z"));
        PaymentAttempt secondAttempt = new PaymentAttempt(UUID.randomUUID(), invoice, PaymentAttemptStatus.SUCCEEDED, Instant.parse("2026-08-01T00:00:00Z"));
        when(paymentAttemptRepository.findByInvoiceId(invoiceId)).thenReturn(List.of(firstAttempt, secondAttempt));

        InvoiceDetail detail = service().getById(invoiceId, customerId);

        assertThat(detail.id()).isEqualTo(invoiceId);
        assertThat(detail.subscriptionId()).isEqualTo(subscriptionId);
        assertThat(detail.planName()).isEqualTo("Pro");
        assertThat(detail.amount()).isEqualByComparingTo("19.00");
        assertThat(detail.paymentAttempts()).extracting(PaymentAttemptView::status)
                .containsExactly(PaymentAttemptStatus.FAILED, PaymentAttemptStatus.SUCCEEDED);
    }
}
