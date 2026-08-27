package com.subscriptionbilling.invoicing.invoice;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InvoiceTest {

    private Invoice newInvoice() {
        return new Invoice(UUID.randomUUID(), UUID.randomUUID(), LocalDate.of(2027, 1, 31), UUID.randomUUID());
    }

    @Test
    void retriesUsedDefaultsToZero() {
        assertThat(newInvoice().getRetriesUsed()).isZero();
    }

    @Test
    void recordRetryAttemptIncrementsRetriesUsed() {
        Invoice invoice = newInvoice();

        invoice.recordRetryAttempt();

        assertThat(invoice.getRetriesUsed()).isEqualTo(1);
    }

    @Test
    void retriesAreNotExhaustedBelowTheCapOfThree() {
        Invoice invoice = newInvoice();

        invoice.recordRetryAttempt();
        invoice.recordRetryAttempt();

        assertThat(invoice.retriesExhausted()).isFalse();
    }

    @Test
    void theThirdRecordedRetryAttemptIsTheTriggerConditionRegardlessOfWhichDayItHappenedOn() {
        Invoice invoice = newInvoice();

        invoice.recordRetryAttempt();
        invoice.recordRetryAttempt();
        invoice.recordRetryAttempt();

        assertThat(invoice.getRetriesUsed()).isEqualTo(3);
        assertThat(invoice.retriesExhausted()).isTrue();
    }

    @Test
    void rejectsARetryAttemptOnceTheCapOfThreeIsReached() {
        Invoice invoice = newInvoice();
        invoice.recordRetryAttempt();
        invoice.recordRetryAttempt();
        invoice.recordRetryAttempt();

        assertThatThrownBy(invoice::recordRetryAttempt).isInstanceOf(IllegalStateException.class);

        assertThat(invoice.getRetriesUsed()).isEqualTo(3);
    }
}
