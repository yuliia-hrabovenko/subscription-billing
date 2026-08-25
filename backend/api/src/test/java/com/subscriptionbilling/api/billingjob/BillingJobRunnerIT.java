package com.subscriptionbilling.api.billingjob;

import com.subscriptionbilling.api.support.AbstractPostgresIntegrationTest;
import com.subscriptionbilling.billingcore.customer.Customer;
import com.subscriptionbilling.billingcore.customer.CustomerRepository;
import com.subscriptionbilling.billingcore.plan.Plan;
import com.subscriptionbilling.billingcore.plan.PlanRepository;
import com.subscriptionbilling.billingcore.plan.PriceVersion;
import com.subscriptionbilling.billingcore.plan.PriceVersionRepository;
import com.subscriptionbilling.billingcore.subscription.Subscription;
import com.subscriptionbilling.billingcore.subscription.SubscriptionRepository;
import com.subscriptionbilling.billingjob.BillingJobRunner;
import com.subscriptionbilling.invoicing.invoice.Invoice;
import com.subscriptionbilling.invoicing.invoice.InvoiceRepository;
import com.subscriptionbilling.invoicing.invoice.PaymentAttempt;
import com.subscriptionbilling.invoicing.invoice.PaymentAttemptRepository;
import com.subscriptionbilling.invoicing.invoice.PaymentAttemptStatus;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves {@link BillingJobRunner#run()} end to end against real Postgres, wired through
 * {@code billing-core}'s and {@code invoicing}'s port implementations and the
 * always-succeeding {@link com.subscriptionbilling.api.support.PaymentGatewayTestConfig}
 * fake — the only module with every one of those on its classpath at once, which is why
 * this (and the selection coverage it absorbs from ticket #10's now-relocated {@code
 * BillingJobRunnerIT}) lives here rather than in {@code billing-core}.
 */
@SpringBootTest
class BillingJobRunnerIT extends AbstractPostgresIntegrationTest {

    @Autowired
    private BillingJobRunner billingJobRunner;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private PlanRepository planRepository;

    @Autowired
    private PriceVersionRepository priceVersionRepository;

    @Autowired
    private SubscriptionRepository subscriptionRepository;

    @Autowired
    private InvoiceRepository invoiceRepository;

    @Autowired
    private PaymentAttemptRepository paymentAttemptRepository;

    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    void runSelectsExactlyTheSubscriptionsDueTodayOrEarlier() {
        LocalDate today = LocalDate.now();
        Plan proPlan = planRepository.findByCode("pro").orElseThrow();

        Subscription overdue = seedSubscription(proPlan, today.minusDays(5));
        Subscription dueToday = seedSubscription(proPlan, today);
        Subscription notYetDue = seedSubscription(proPlan, today.plusDays(5));
        Subscription noDueDate = seedSubscription(proPlan, null);

        List<UUID> selected = billingJobRunner.run();

        assertThat(selected).contains(overdue.getId(), dueToday.getId());
        assertThat(selected).doesNotContain(notYetDue.getId(), noDueDate.getId());
    }

    @Test
    void runSelectsASubscriptionSeveralDaysOverdueSimulatingAMissedRun() {
        LocalDate today = LocalDate.now();
        Plan proPlan = planRepository.findByCode("pro").orElseThrow();
        Subscription severalDaysOverdue = seedSubscription(proPlan, today.minusDays(10));

        List<UUID> selected = billingJobRunner.run();

        assertThat(selected).contains(severalDaysOverdue.getId());
    }

    @Test
    void aSuccessfulChargeCreatesExactlyOneInvoiceAndOneSucceededPaymentAttemptForTheBillingPeriod() {
        Plan proPlan = planRepository.findByCode("pro").orElseThrow();
        LocalDate billingPeriod = LocalDate.of(2026, 1, 24);
        PriceVersion currentPrice = priceVersionRepository
                .findTopByPlanIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(
                        proPlan.getId(), billingPeriod.atStartOfDay(ZoneOffset.UTC).toInstant())
                .orElseThrow();
        Subscription subscription = seedDueSubscription(proPlan, Instant.parse("2026-01-24T00:00:00Z"), billingPeriod);

        billingJobRunner.run();

        Optional<Invoice> invoice = invoiceRepository.findBySubscriptionIdAndBillingPeriod(subscription.getId(), billingPeriod);
        assertThat(invoice).isPresent();
        assertThat(invoice.get().getPriceVersionId()).isEqualTo(currentPrice.getId());

        List<PaymentAttempt> attempts = paymentAttemptRepository.findByInvoiceId(invoice.get().getId());
        assertThat(attempts).singleElement().satisfies(attempt ->
                assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.SUCCEEDED));
    }

    @Test
    void aSuccessfulChargeAdvancesDueDateApplyingMonthEndClampingForAJan31AnchoredSubscription() {
        Plan proPlan = planRepository.findByCode("pro").orElseThrow();
        // Anchored on the 31st; this cycle's billing date is Jan 31, 2026 -- rolling into
        // February (28 days in 2026, a non-leap year) must clamp rather than overflow.
        Subscription subscription = seedDueSubscription(
                proPlan, Instant.parse("2026-01-31T00:00:00Z"), LocalDate.of(2026, 1, 31));

        billingJobRunner.run();

        Subscription reloaded = subscriptionRepository.findById(subscription.getId()).orElseThrow();
        assertThat(reloaded.getDueDate()).isEqualTo(LocalDate.of(2026, 2, 28));
    }

    @Test
    void aSuccessfulChargeIncrementsTheSubscriptionsProcessedCounterAndRecordsTheJobDurationTimer() {
        // Deltas rather than exact counts: other test methods in this class leave their
        // own seeded Subscriptions behind in the shared Testcontainers Postgres (see the
        // class Javadoc), and those can still be due when this method's run() executes,
        // adding to the same Meter instances.
        Plan proPlan = planRepository.findByCode("pro").orElseThrow();
        seedDueSubscription(proPlan, Instant.parse("2026-03-15T00:00:00Z"), LocalDate.of(2026, 3, 15));
        double processedBefore = meterRegistry.get("billing_job_subscriptions_processed_total").counter().count();
        long durationCountBefore = meterRegistry.get("billing_job_duration_seconds").timer().count();

        billingJobRunner.run();

        double processedAfter = meterRegistry.get("billing_job_subscriptions_processed_total").counter().count();
        long durationCountAfter = meterRegistry.get("billing_job_duration_seconds").timer().count();
        assertThat(processedAfter).isGreaterThanOrEqualTo(processedBefore + 1.0);
        assertThat(durationCountAfter).isEqualTo(durationCountBefore + 1L);
    }

    private Subscription seedSubscription(Plan plan, LocalDate dueDate) {
        Subscription subscription = Subscription.startPaidImmediately(
                UUID.randomUUID(), seedCustomer(), plan, Instant.now());
        if (dueDate != null) {
            subscription.advanceDueDate(dueDate);
        }
        return subscriptionRepository.saveAndFlush(subscription);
    }

    private Subscription seedDueSubscription(Plan plan, Instant billingCycleAnchor, LocalDate billingPeriod) {
        Subscription subscription = Subscription.startPaidImmediately(
                UUID.randomUUID(), seedCustomer(), plan, billingCycleAnchor);
        subscription.advanceDueDate(billingPeriod);
        return subscriptionRepository.saveAndFlush(subscription);
    }

    private Customer seedCustomer() {
        Customer customer = new Customer(UUID.randomUUID(), "billing-job-it-" + UUID.randomUUID() + "@example.com");
        customer.setPaymentMethodToken("tok_visa");
        return customerRepository.saveAndFlush(customer);
    }
}
