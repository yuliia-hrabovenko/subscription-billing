package com.subscriptionbilling.api;

import com.subscriptionbilling.api.support.AbstractPostgresIntegrationTest;
import com.subscriptionbilling.api.support.PaymentGatewayTestConfig;
import com.subscriptionbilling.billingcore.customer.Customer;
import com.subscriptionbilling.billingcore.customer.CustomerRepository;
import com.subscriptionbilling.billingcore.plan.Plan;
import com.subscriptionbilling.billingcore.plan.PlanRepository;
import com.subscriptionbilling.billingcore.subscription.Subscription;
import com.subscriptionbilling.billingcore.subscription.SubscriptionRepository;
import com.subscriptionbilling.invoicing.invoice.Invoice;
import com.subscriptionbilling.invoicing.invoice.InvoiceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves {@code --job=billing-run} — the argument the Kubernetes CronJob
 * (k8s/billing-job-cronjob.yaml) and the manual {@code make billing-job} trigger both
 * pass to the same application image — actually drives a real charge through {@link
 * com.subscriptionbilling.billingjob.BillingJobRunner} against real Postgres, not just
 * a no-op boot.
 *
 * <p>{@link ApiApplication#run} is invoked directly rather than via {@code main}, since
 * {@code main} calls {@code System.exit} — fatal to the test JVM itself.
 */
@SpringBootTest
class BillingJobEntryPointIT extends AbstractPostgresIntegrationTest {

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private PlanRepository planRepository;

    @Autowired
    private SubscriptionRepository subscriptionRepository;

    @Autowired
    private InvoiceRepository invoiceRepository;

    @Test
    void jobArgumentChargesADueSubscriptionAgainstRealPostgresAndExitsZero() {
        Plan proPlan = planRepository.findByCode("pro").orElseThrow();
        Customer customer = new Customer(UUID.randomUUID(), "cronjob-it-" + UUID.randomUUID() + "@example.com");
        customer.setPaymentMethodToken("tok_visa");
        customerRepository.saveAndFlush(customer);
        LocalDate billingPeriod = LocalDate.of(2026, 2, 1);
        Subscription subscription = Subscription.startPaidImmediately(
                UUID.randomUUID(), customer, proPlan, Instant.parse("2026-02-01T00:00:00Z"));
        subscription.advanceDueDate(billingPeriod);
        subscriptionRepository.saveAndFlush(subscription);

        int exitCode = ApiApplication.run(new String[] {
                "--job=billing-run",
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword()
        }, PaymentGatewayTestConfig.class);

        assertThat(exitCode).isEqualTo(0);
        Optional<Invoice> invoice =
                invoiceRepository.findBySubscriptionIdAndBillingPeriod(subscription.getId(), billingPeriod);
        assertThat(invoice).isPresent();
    }
}
