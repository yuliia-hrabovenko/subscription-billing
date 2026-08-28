package com.subscriptionbilling.invoicing.invoice;

import com.subscriptionbilling.invoicing.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
class InvoiceRepositoryTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private InvoiceRepository invoiceRepository;

    @Test
    void savesAndFetchesAnInvoiceSnapshottingThePriceVersionCharged() {
        UUID subscriptionId = UUID.randomUUID();
        UUID priceVersionId = UUID.randomUUID();
        LocalDate billingPeriod = LocalDate.of(2027, 1, 31);
        Invoice invoice = new Invoice(UUID.randomUUID(), subscriptionId, billingPeriod, priceVersionId);

        invoiceRepository.saveAndFlush(invoice);

        Optional<Invoice> found = invoiceRepository.findById(invoice.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getSubscriptionId()).isEqualTo(subscriptionId);
        assertThat(found.get().getBillingPeriod()).isEqualTo(billingPeriod);
        assertThat(found.get().getPriceVersionId()).isEqualTo(priceVersionId);
        assertThat(found.get().getStatus()).isEqualTo(InvoiceStatus.OPEN);
        assertThat(found.get().getCreatedAt()).isNotNull();
    }

    @Test
    void findsAnInvoiceBySubscriptionAndBillingPeriod() {
        UUID subscriptionId = UUID.randomUUID();
        LocalDate billingPeriod = LocalDate.of(2027, 3, 31);
        Invoice invoice = new Invoice(UUID.randomUUID(), subscriptionId, billingPeriod, UUID.randomUUID());
        invoiceRepository.saveAndFlush(invoice);

        Optional<Invoice> found = invoiceRepository.findBySubscriptionIdAndBillingPeriod(subscriptionId, billingPeriod);

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(invoice.getId());
    }

    @Test
    void rejectsASecondInvoiceForTheSameSubscriptionAndBillingPeriod() {
        // Proves Invariant 6 at the database level -- the mechanism the billing job's
        // crash/duplicate-run idempotency guarantee relies on, not merely an
        // application-level check.
        UUID subscriptionId = UUID.randomUUID();
        LocalDate billingPeriod = LocalDate.of(2027, 2, 28);
        invoiceRepository.saveAndFlush(new Invoice(UUID.randomUUID(), subscriptionId, billingPeriod, UUID.randomUUID()));

        Invoice duplicate = new Invoice(UUID.randomUUID(), subscriptionId, billingPeriod, UUID.randomUUID());

        assertThatThrownBy(() -> invoiceRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void allowsTheSameSubscriptionToHaveInvoicesForDifferentBillingPeriods() {
        UUID subscriptionId = UUID.randomUUID();
        invoiceRepository.saveAndFlush(
                new Invoice(UUID.randomUUID(), subscriptionId, LocalDate.of(2027, 1, 31), UUID.randomUUID()));

        Invoice nextCycle = new Invoice(UUID.randomUUID(), subscriptionId, LocalDate.of(2027, 2, 28), UUID.randomUUID());
        invoiceRepository.saveAndFlush(nextCycle);

        assertThat(invoiceRepository.findById(nextCycle.getId())).isPresent();
    }

    @Test
    void findPageBySubscriptionIdOrdersNewestFirstAndIgnoresOtherSubscriptions() {
        UUID subscriptionId = UUID.randomUUID();
        Invoice oldest = invoiceRepository.saveAndFlush(
                new Invoice(UUID.randomUUID(), subscriptionId, LocalDate.of(2027, 1, 31), UUID.randomUUID()));
        Invoice newest = invoiceRepository.saveAndFlush(
                new Invoice(UUID.randomUUID(), subscriptionId, LocalDate.of(2027, 2, 28), UUID.randomUUID()));
        invoiceRepository.saveAndFlush(
                new Invoice(UUID.randomUUID(), UUID.randomUUID(), LocalDate.of(2027, 2, 28), UUID.randomUUID()));

        List<Invoice> page = invoiceRepository.findPageBySubscriptionId(subscriptionId, null, null, PageRequest.of(0, 10));

        assertThat(page).extracting(Invoice::getId).containsExactly(newest.getId(), oldest.getId());
    }

    @Test
    void findPageBySubscriptionIdWithACursorNeitherRepeatsNorSkipsARowAcrossTwoPages() {
        // Deliberately doesn't assert on createdAt spacing -- the query's own
        // createdAt-desc/id-desc tie-break is what guarantees no repeat/skip even when
        // rows share the same createdAt (a coarse clock can produce that within a tight
        // test loop), not any assumption about timestamp precision.
        UUID subscriptionId = UUID.randomUUID();
        Invoice first = invoiceRepository.saveAndFlush(
                new Invoice(UUID.randomUUID(), subscriptionId, LocalDate.of(2027, 1, 31), UUID.randomUUID()));
        Invoice second = invoiceRepository.saveAndFlush(
                new Invoice(UUID.randomUUID(), subscriptionId, LocalDate.of(2027, 2, 28), UUID.randomUUID()));
        Invoice third = invoiceRepository.saveAndFlush(
                new Invoice(UUID.randomUUID(), subscriptionId, LocalDate.of(2027, 3, 31), UUID.randomUUID()));

        List<Invoice> firstPage = invoiceRepository.findPageBySubscriptionId(subscriptionId, null, null, PageRequest.of(0, 2));
        assertThat(firstPage).hasSize(2);
        Invoice cursorRow = firstPage.get(1);

        List<Invoice> secondPage = invoiceRepository.findPageBySubscriptionId(
                subscriptionId, cursorRow.getCreatedAt(), cursorRow.getId(), PageRequest.of(0, 2));

        assertThat(secondPage).extracting(Invoice::getId).doesNotContainAnyElementsOf(
                firstPage.stream().map(Invoice::getId).toList());
        List<UUID> allIds = new ArrayList<>();
        firstPage.forEach(invoice -> allIds.add(invoice.getId()));
        secondPage.forEach(invoice -> allIds.add(invoice.getId()));
        assertThat(allIds).containsExactlyInAnyOrder(first.getId(), second.getId(), third.getId());
    }
}
