package com.subscriptionbilling.invoicing.invoice;

import com.subscriptionbilling.invoicing.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class PaymentAttemptRepositoryTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private InvoiceRepository invoiceRepository;

    @Autowired
    private PaymentAttemptRepository paymentAttemptRepository;

    @Test
    void savesAndFetchesAPaymentAttemptAgainstItsInvoice() {
        Invoice invoice = invoiceRepository.saveAndFlush(
                new Invoice(UUID.randomUUID(), UUID.randomUUID(), LocalDate.of(2027, 1, 31), UUID.randomUUID()));
        Instant attemptedAt = Instant.now();
        PaymentAttempt attempt = new PaymentAttempt(UUID.randomUUID(), invoice, PaymentAttemptStatus.FAILED, attemptedAt);

        paymentAttemptRepository.saveAndFlush(attempt);

        Optional<PaymentAttempt> found = paymentAttemptRepository.findById(attempt.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getInvoice().getId()).isEqualTo(invoice.getId());
        assertThat(found.get().getStatus()).isEqualTo(PaymentAttemptStatus.FAILED);
        assertThat(found.get().getAttemptedAt()).isEqualTo(attemptedAt);
    }

    @Test
    void findsEveryAttemptForAnInvoiceOldestFirst() {
        Invoice invoice = invoiceRepository.saveAndFlush(
                new Invoice(UUID.randomUUID(), UUID.randomUUID(), LocalDate.of(2027, 4, 30), UUID.randomUUID()));
        Instant now = Instant.now();
        // Saved out of chronological order, so the assertion below only passes if
        // findByInvoiceId actually orders by attemptedAt rather than insertion order.
        PaymentAttempt retry = new PaymentAttempt(UUID.randomUUID(), invoice, PaymentAttemptStatus.SUCCEEDED, now.plusSeconds(60));
        PaymentAttempt initial = new PaymentAttempt(UUID.randomUUID(), invoice, PaymentAttemptStatus.FAILED, now);
        paymentAttemptRepository.saveAndFlush(retry);
        paymentAttemptRepository.saveAndFlush(initial);

        List<PaymentAttempt> attempts = paymentAttemptRepository.findByInvoiceId(invoice.getId());

        assertThat(attempts).extracting(PaymentAttempt::getId).containsExactly(initial.getId(), retry.getId());
    }

    @Test
    void findsAPaymentAttemptByItsGatewayReference() {
        Invoice invoice = invoiceRepository.saveAndFlush(
                new Invoice(UUID.randomUUID(), UUID.randomUUID(), LocalDate.of(2027, 5, 31), UUID.randomUUID()));
        PaymentAttempt attempt = new PaymentAttempt(
                UUID.randomUUID(), invoice, PaymentAttemptStatus.SUCCEEDED, Instant.now(), "gw-txn-lookup-1");
        paymentAttemptRepository.saveAndFlush(attempt);

        Optional<PaymentAttempt> found = paymentAttemptRepository.findFirstByGatewayReference("gw-txn-lookup-1");

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(attempt.getId());
    }

    @Test
    void findByGatewayReferenceReturnsEmptyWhenNoAttemptCarriesIt() {
        Optional<PaymentAttempt> found = paymentAttemptRepository.findFirstByGatewayReference("gw-does-not-exist");

        assertThat(found).isEmpty();
    }
}
