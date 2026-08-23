package com.subscriptionbilling.invoicing.invoice;

import com.subscriptionbilling.invoicing.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDate;
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
}
