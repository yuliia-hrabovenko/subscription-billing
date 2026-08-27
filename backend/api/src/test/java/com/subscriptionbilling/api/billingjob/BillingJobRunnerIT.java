package com.subscriptionbilling.api.billingjob;

import com.subscriptionbilling.api.support.AbstractPostgresIntegrationTest;
import com.subscriptionbilling.api.support.CountingPaymentGatewayClient;
import com.subscriptionbilling.api.support.PaymentGatewayTestConfig;
import com.subscriptionbilling.audit.AuditLogEntry;
import com.subscriptionbilling.audit.AuditLogEntryRepository;
import com.subscriptionbilling.billingcore.customer.Customer;
import com.subscriptionbilling.billingcore.customer.CustomerRepository;
import com.subscriptionbilling.billingcore.plan.Plan;
import com.subscriptionbilling.billingcore.plan.PlanRepository;
import com.subscriptionbilling.billingcore.plan.PriceVersion;
import com.subscriptionbilling.billingcore.plan.PriceVersionRepository;
import com.subscriptionbilling.billingcore.subscription.Subscription;
import com.subscriptionbilling.billingcore.subscription.SubscriptionRepository;
import com.subscriptionbilling.billingcore.subscription.SubscriptionState;
import com.subscriptionbilling.billingjob.BillingJobRunner;
import com.subscriptionbilling.billingjob.anchor.AnchorDate;
import com.subscriptionbilling.invoicing.invoice.Invoice;
import com.subscriptionbilling.invoicing.invoice.InvoiceRepository;
import com.subscriptionbilling.invoicing.invoice.PaymentAttempt;
import com.subscriptionbilling.invoicing.invoice.PaymentAttemptRepository;
import com.subscriptionbilling.invoicing.invoice.PaymentAttemptStatus;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves {@link BillingJobRunner#run()} end to end against real Postgres, wired through
 * {@code billing-core}'s, {@code invoicing}'s, and {@code dunning}'s port implementations
 * and the token-driven {@link com.subscriptionbilling.api.support.PaymentGatewayTestConfig}
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

    @Autowired
    private CountingPaymentGatewayClient paymentGatewayClient;

    @Autowired
    private AuditLogEntryRepository auditLogEntryRepository;

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
    void aSuccessfulChargeCatchingUpAMissedRunAdvancesFromTheOriginalDueDateNotFromToday() {
        // Anchored to the 15th; due_date is well in the past by the time this run picks
        // it up, simulating a run missed by a deploy or outage. The next due date must
        // land on the 15th of the following month -- derived from the original due date,
        // never from whatever day the catch-up run actually executes on.
        Plan proPlan = planRepository.findByCode("pro").orElseThrow();
        Subscription subscription = seedDueSubscription(
                proPlan, Instant.parse("2026-06-15T00:00:00Z"), LocalDate.of(2026, 6, 15));

        billingJobRunner.run();

        Subscription reloaded = subscriptionRepository.findById(subscription.getId()).orElseThrow();
        assertThat(reloaded.getDueDate()).isEqualTo(LocalDate.of(2026, 7, 15));
    }

    @Test
    void aSuccessfulChargeCatchingUpAMissedRunForAMonthEndAnchoredSubscriptionStillClampsCorrectly() {
        // Combines the catch-up guarantee above with month-end clamping: anchored to the
        // 31st, still overdue when this run finally catches it up, must clamp into June's
        // last day (30) rather than drifting to whatever day the catch-up run executes on.
        Plan proPlan = planRepository.findByCode("pro").orElseThrow();
        Subscription subscription = seedDueSubscription(
                proPlan, Instant.parse("2026-05-31T00:00:00Z"), LocalDate.of(2026, 5, 31));

        billingJobRunner.run();

        Subscription reloaded = subscriptionRepository.findById(subscription.getId()).orElseThrow();
        assertThat(reloaded.getDueDate()).isEqualTo(LocalDate.of(2026, 6, 30));
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

    @Test
    void aDeclinedChargeRecordsAFailedPaymentAttemptSchedulesTheDayOneDunningRetryAndSuspendsTheSubscription() {
        Plan proPlan = planRepository.findByCode("pro").orElseThrow();
        LocalDate billingPeriod = LocalDate.of(2026, 4, 10);
        Subscription subscription = seedDueSubscription(
                proPlan, Instant.parse("2026-04-10T00:00:00Z"), billingPeriod, PaymentGatewayTestConfig.DECLINE_TOKEN);

        billingJobRunner.run();

        Optional<Invoice> invoice = invoiceRepository.findBySubscriptionIdAndBillingPeriod(subscription.getId(), billingPeriod);
        assertThat(invoice).isPresent();
        List<PaymentAttempt> attempts = paymentAttemptRepository.findByInvoiceId(invoice.get().getId());
        assertThat(attempts).singleElement().satisfies(attempt ->
                assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.FAILED));

        Subscription reloaded = subscriptionRepository.findById(subscription.getId()).orElseThrow();
        // Moved off billingPeriod to Dunning's day-1 retry offset, not left unchanged --
        // this is what makes the billing job's existing due_date <= today query pick this
        // Subscription back up for the retry with no new query needed.
        assertThat(reloaded.getDueDate()).isEqualTo(LocalDate.now(ZoneOffset.UTC).plusDays(1));
        assertThat(reloaded.getState()).isEqualTo(SubscriptionState.SUSPENDED);
    }

    @Test
    void aSubscriptionWithAStillFailingCardRunsTheFullDayOneThreeSevenScheduleAndEndsCanceledWithFourPaymentAttemptsOnOneInvoice() {
        // Required test: the full bounded retry loop, driven end to end through real
        // runs of the job -- each run's outcome is what schedules the next, exactly as
        // production does. Day 1/3/7 are computed by DunningSchedule from the Invoice's
        // real creation instant (no clock seam on that path), so each step's due_date is
        // force-moved back to today via the Subscription aggregate's own scheduleRetry
        // (the same mechanism Dunning itself uses) to simulate that day having arrived,
        // rather than the test waiting on the calendar.
        Plan proPlan = planRepository.findByCode("pro").orElseThrow();
        LocalDate billingPeriod = LocalDate.of(2026, 2, 1);
        Subscription subscription = seedDueSubscription(
                proPlan, Instant.parse("2026-02-01T00:00:00Z"), billingPeriod, PaymentGatewayTestConfig.DECLINE_TOKEN);
        double attemptsBefore = meterRegistry.get("billing_job_dunning_attempts_total").counter().count();
        double exhaustionsBefore = meterRegistry.get("billing_job_dunning_exhaustions_total").counter().count();

        billingJobRunner.run(); // initial charge fails -> suspended, day-1 retry scheduled
        forceDueToday(subscription.getId());
        billingJobRunner.run(); // day-1 retry fails -> retriesUsed=1, day-3 scheduled
        forceDueToday(subscription.getId());
        billingJobRunner.run(); // day-3 retry fails -> retriesUsed=2, day-7 scheduled
        forceDueToday(subscription.getId());
        billingJobRunner.run(); // day-7 retry fails -> retriesUsed=3, exhausted -> canceled

        Optional<Invoice> invoice = invoiceRepository.findBySubscriptionIdAndBillingPeriod(subscription.getId(), billingPeriod);
        assertThat(invoice).isPresent();
        assertThat(invoice.get().getRetriesUsed()).isEqualTo(3);
        List<PaymentAttempt> attempts = paymentAttemptRepository.findByInvoiceId(invoice.get().getId());
        assertThat(attempts).hasSize(4);
        assertThat(attempts).allSatisfy(attempt -> assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.FAILED));

        Subscription reloaded = subscriptionRepository.findById(subscription.getId()).orElseThrow();
        assertThat(reloaded.getState()).isEqualTo(SubscriptionState.CANCELED);
        assertThat(reloaded.getDunningBillingPeriod()).isNull();

        assertThat(meterRegistry.get("billing_job_dunning_attempts_total").counter().count())
                .isGreaterThanOrEqualTo(attemptsBefore + 3.0);
        assertThat(meterRegistry.get("billing_job_dunning_exhaustions_total").counter().count())
                .isGreaterThanOrEqualTo(exhaustionsBefore + 1.0);

        List<AuditLogEntry> entries = auditLogEntryRepository.findBySubscriptionId(subscription.getId());
        assertThat(entries).anySatisfy(entry -> {
            assertThat(entry.getOldState()).isEqualTo("SUSPENDED");
            assertThat(entry.getNewState()).isEqualTo("CANCELED");
        });
    }

    @Test
    void aScheduledDunningRetryThatSucceedsRestoresActiveAttachesToTheSameInvoiceAndStopsFurtherRetries() {
        Plan proPlan = planRepository.findByCode("pro").orElseThrow();
        LocalDate billingPeriod = LocalDate.of(2026, 2, 5);
        Subscription subscription = seedDueSubscription(
                proPlan, Instant.parse("2026-02-05T00:00:00Z"), billingPeriod, PaymentGatewayTestConfig.DECLINE_TOKEN);
        double recoveriesBefore = meterRegistry.get("billing_job_dunning_recoveries_total").counter().count();

        billingJobRunner.run(); // initial charge fails -> suspended, day-1 retry scheduled
        forceDueToday(subscription.getId());
        // Simulates the Customer updating their card before the day-1 retry fires.
        Customer customer = customerRepository.findById(subscription.getCustomer().getId()).orElseThrow();
        customer.setPaymentMethodToken("tok_visa");
        customerRepository.saveAndFlush(customer);

        billingJobRunner.run(); // day-1 retry succeeds -> restored to active

        Optional<Invoice> invoice = invoiceRepository.findBySubscriptionIdAndBillingPeriod(subscription.getId(), billingPeriod);
        assertThat(invoice).isPresent();
        List<PaymentAttempt> attempts = paymentAttemptRepository.findByInvoiceId(invoice.get().getId());
        assertThat(attempts).hasSize(2);
        assertThat(attempts.get(0).getStatus()).isEqualTo(PaymentAttemptStatus.FAILED);
        assertThat(attempts.get(1).getStatus()).isEqualTo(PaymentAttemptStatus.SUCCEEDED);

        Subscription reloaded = subscriptionRepository.findById(subscription.getId()).orElseThrow();
        assertThat(reloaded.getState()).isEqualTo(SubscriptionState.ACTIVE);
        assertThat(reloaded.getDunningBillingPeriod()).isNull();
        // Stops further scheduled retries for this cycle: due_date lands on the ordinary
        // next renewal derived from the original billing period, not on day 3/7.
        assertThat(reloaded.getDueDate()).isEqualTo(new AnchorDate(billingPeriod.getDayOfMonth()).next(billingPeriod));

        assertThat(meterRegistry.get("billing_job_dunning_recoveries_total").counter().count())
                .isGreaterThanOrEqualTo(recoveriesBefore + 1.0);

        List<AuditLogEntry> entries = auditLogEntryRepository.findBySubscriptionId(subscription.getId());
        assertThat(entries).anySatisfy(entry -> {
            assertThat(entry.getOldState()).isEqualTo("SUSPENDED");
            assertThat(entry.getNewState()).isEqualTo("ACTIVE");
        });
    }

    @Test
    void aTransientGatewayFailureDoesNotCreateAPaymentAttemptOrChangeSubscriptionState() {
        Plan proPlan = planRepository.findByCode("pro").orElseThrow();
        LocalDate billingPeriod = LocalDate.of(2026, 4, 12);
        Subscription subscription = seedDueSubscription(proPlan, Instant.parse("2026-04-12T00:00:00Z"),
                billingPeriod, PaymentGatewayTestConfig.TRANSIENT_FAILURE_TOKEN);

        billingJobRunner.run();

        assertThat(invoiceRepository.findBySubscriptionIdAndBillingPeriod(subscription.getId(), billingPeriod)).isEmpty();
        Subscription reloaded = subscriptionRepository.findById(subscription.getId()).orElseThrow();
        assertThat(reloaded.getDueDate()).isEqualTo(billingPeriod);
        assertThat(reloaded.getState()).isEqualTo(SubscriptionState.ACTIVE);
    }

    @Test
    void aDeclinedChargeIncrementsTheDeclinedChargesCounter() {
        // Delta rather than exact count -- see the class Javadoc.
        Plan proPlan = planRepository.findByCode("pro").orElseThrow();
        seedDueSubscription(proPlan, Instant.parse("2026-04-20T00:00:00Z"),
                LocalDate.of(2026, 4, 20), PaymentGatewayTestConfig.DECLINE_TOKEN);
        double declinedBefore = meterRegistry.get("billing_job_declined_charges_total").counter().count();

        billingJobRunner.run();

        double declinedAfter = meterRegistry.get("billing_job_declined_charges_total").counter().count();
        assertThat(declinedAfter).isGreaterThanOrEqualTo(declinedBefore + 1.0);
    }

    @Test
    void runningTheJobTwiceInARowForTheSameSubscriptionChargesTheGatewayAtMostOnce() {
        // Declined: the first run suspends and moves due_date to Dunning's day-1 retry
        // offset (tomorrow), so the second run's real due-selection query naturally
        // excludes it -- a genuine two-real-runs proof of required test (a), rather than
        // simulating "already recorded" by hand, though it now exercises the due-date
        // selection guard rather than the invoiceAlreadyRecorded pre-check (which
        // aSubscriptionAlreadyInvoicedForItsDueDateIsSkippedWithoutChargingTheGatewayAgain
        // below covers instead).
        Plan proPlan = planRepository.findByCode("pro").orElseThrow();
        LocalDate billingPeriod = LocalDate.of(2026, 6, 1);
        Subscription subscription = seedDueSubscription(proPlan, Instant.parse("2026-06-01T00:00:00Z"),
                billingPeriod, PaymentGatewayTestConfig.DECLINE_TOKEN);
        int chargesBefore = paymentGatewayClient.chargeCount();

        billingJobRunner.run();
        billingJobRunner.run();

        Optional<Invoice> invoice = invoiceRepository.findBySubscriptionIdAndBillingPeriod(subscription.getId(), billingPeriod);
        assertThat(invoice).isPresent();
        List<PaymentAttempt> attempts = paymentAttemptRepository.findByInvoiceId(invoice.get().getId());
        assertThat(attempts).hasSize(1);
        assertThat(paymentGatewayClient.chargeCount() - chargesBefore).isEqualTo(1);
    }

    @Test
    void aSubscriptionAlreadyInvoicedForItsDueDateIsSkippedWithoutChargingTheGatewayAgain() {
        // Simulates the common "job re-run after a perceived-but-not-actual failure"
        // case: a prior run recorded the Invoice/PaymentAttempt for this cycle but
        // crashed before advancing due_date, so the Subscription still looks due today.
        Plan proPlan = planRepository.findByCode("pro").orElseThrow();
        LocalDate billingPeriod = LocalDate.of(2026, 5, 5);
        PriceVersion currentPrice = priceVersionRepository
                .findTopByPlanIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(
                        proPlan.getId(), billingPeriod.atStartOfDay(ZoneOffset.UTC).toInstant())
                .orElseThrow();
        Subscription subscription = seedDueSubscription(proPlan, Instant.parse("2026-05-05T00:00:00Z"), billingPeriod);
        Invoice invoice = invoiceRepository.saveAndFlush(
                new Invoice(UUID.randomUUID(), subscription.getId(), billingPeriod, currentPrice.getId()));
        paymentAttemptRepository.saveAndFlush(
                new PaymentAttempt(UUID.randomUUID(), invoice, PaymentAttemptStatus.SUCCEEDED, Instant.now()));

        billingJobRunner.run();

        List<PaymentAttempt> attempts = paymentAttemptRepository.findByInvoiceId(invoice.getId());
        assertThat(attempts).hasSize(1);
        Subscription reloaded = subscriptionRepository.findById(subscription.getId()).orElseThrow();
        assertThat(reloaded.getDueDate()).isEqualTo(billingPeriod);
    }

    @Test
    void aSkippedDuplicateChargeIncrementsTheDuplicateChargeSkippedCounter() {
        // Delta rather than exact count -- see the class Javadoc.
        Plan proPlan = planRepository.findByCode("pro").orElseThrow();
        LocalDate billingPeriod = LocalDate.of(2026, 5, 8);
        PriceVersion currentPrice = priceVersionRepository
                .findTopByPlanIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(
                        proPlan.getId(), billingPeriod.atStartOfDay(ZoneOffset.UTC).toInstant())
                .orElseThrow();
        Subscription subscription = seedDueSubscription(proPlan, Instant.parse("2026-05-08T00:00:00Z"), billingPeriod);
        invoiceRepository.saveAndFlush(
                new Invoice(UUID.randomUUID(), subscription.getId(), billingPeriod, currentPrice.getId()));
        double skippedBefore = meterRegistry.get("billing_job_duplicate_charge_skipped_total").counter().count();

        billingJobRunner.run();

        double skippedAfter = meterRegistry.get("billing_job_duplicate_charge_skipped_total").counter().count();
        assertThat(skippedAfter).isGreaterThanOrEqualTo(skippedBefore + 1.0);
    }

    @Test
    void aTrialConversionChargeThatSucceedsActivatesTheSubscriptionOpensTheBillingCycleAnchoredToTodayAndCreatesExactlyOneInvoice() {
        // Proves required test (c): a full Trial -> active conversion charge succeeds
        // through the exact same success path as an ordinary renewal (the assertions
        // mirror aSuccessfulChargeCreatesExactlyOneInvoiceAndOneSucceededPaymentAttemptForTheBillingPeriod above).
        Plan proPlan = planRepository.findByCode("pro").orElseThrow();
        LocalDate today = LocalDate.now();
        Subscription subscription = seedDueTrialSubscription(proPlan, today, "tok_visa");

        billingJobRunner.run();

        Subscription reloaded = subscriptionRepository.findById(subscription.getId()).orElseThrow();
        assertThat(reloaded.getState()).isEqualTo(SubscriptionState.ACTIVE);
        assertThat(reloaded.getBillingCycleAnchor()).isNotNull();
        assertThat(reloaded.getTrialEndsAt()).isNull();
        assertThat(reloaded.getDueDate()).isEqualTo(new AnchorDate(today.getDayOfMonth()).next(today));

        Optional<Invoice> invoice = invoiceRepository.findBySubscriptionIdAndBillingPeriod(subscription.getId(), today);
        assertThat(invoice).isPresent();
        List<PaymentAttempt> attempts = paymentAttemptRepository.findByInvoiceId(invoice.get().getId());
        assertThat(attempts).singleElement().satisfies(attempt ->
                assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.SUCCEEDED));

        List<AuditLogEntry> entries = auditLogEntryRepository.findBySubscriptionId(subscription.getId());
        assertThat(entries).anySatisfy(entry -> {
            assertThat(entry.getOldState()).isEqualTo("TRIALING");
            assertThat(entry.getNewState()).isEqualTo("ACTIVE");
        });
    }

    @Test
    void aTrialConversionChargeThatIsDeclinedSuspendsTheSubscriptionAndSchedulesTheDayOneDunningRetryThroughTheSameDunningHandoffAsARenewalDecline() {
        Plan proPlan = planRepository.findByCode("pro").orElseThrow();
        LocalDate trialEndDate = LocalDate.now(ZoneOffset.UTC);
        Subscription subscription = seedDueTrialSubscription(proPlan, trialEndDate, PaymentGatewayTestConfig.DECLINE_TOKEN);

        billingJobRunner.run();

        Subscription reloaded = subscriptionRepository.findById(subscription.getId()).orElseThrow();
        assertThat(reloaded.getState()).isEqualTo(SubscriptionState.SUSPENDED);
        assertThat(reloaded.getBillingCycleAnchor()).isNull();
        // Dunning's day-1 retry offset, not the pre-failure trial end date: proves the
        // real DunningHandoff (not the immediate-suspend-only placeholder it replaced)
        // is wired in.
        assertThat(reloaded.getDueDate()).isEqualTo(trialEndDate.plusDays(1));

        Optional<Invoice> invoice = invoiceRepository.findBySubscriptionIdAndBillingPeriod(subscription.getId(), trialEndDate);
        assertThat(invoice).isPresent();
        List<PaymentAttempt> attempts = paymentAttemptRepository.findByInvoiceId(invoice.get().getId());
        assertThat(attempts).singleElement().satisfies(attempt ->
                assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.FAILED));

        List<AuditLogEntry> entries = auditLogEntryRepository.findBySubscriptionId(subscription.getId());
        assertThat(entries).anySatisfy(entry -> {
            assertThat(entry.getOldState()).isEqualTo("TRIALING");
            assertThat(entry.getNewState()).isEqualTo("SUSPENDED");
        });
    }

    @Test
    void aPendingPaidToPaidPlanChangeIsChargedAtTheNewPlansPriceOnItsDueDateAndSwitchesThePlanAfterward() {
        Plan proPlan = planRepository.findByCode("pro").orElseThrow();
        Plan enterprisePlan = seedPaidPlan("enterprise-" + UUID.randomUUID(), "Enterprise", new BigDecimal("49.00"));
        LocalDate billingPeriod = LocalDate.of(2026, 7, 10);
        Subscription subscription = seedDueSubscription(proPlan, Instant.parse("2026-07-10T00:00:00Z"), billingPeriod);
        subscription.schedulePlanChange(enterprisePlan, false, Instant.now());
        subscriptionRepository.saveAndFlush(subscription);
        PriceVersion enterprisePrice = priceVersionRepository
                .findTopByPlanIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(
                        enterprisePlan.getId(), billingPeriod.atStartOfDay(ZoneOffset.UTC).toInstant())
                .orElseThrow();

        billingJobRunner.run();

        Subscription reloaded = subscriptionRepository.findById(subscription.getId()).orElseThrow();
        assertThat(reloaded.getPlan().getId()).isEqualTo(enterprisePlan.getId());
        assertThat(reloaded.getPendingPlanChange()).isNull();

        Optional<Invoice> invoice = invoiceRepository.findBySubscriptionIdAndBillingPeriod(subscription.getId(), billingPeriod);
        assertThat(invoice).isPresent();
        assertThat(invoice.get().getPriceVersionId()).isEqualTo(enterprisePrice.getId());
        List<PaymentAttempt> attempts = paymentAttemptRepository.findByInvoiceId(invoice.get().getId());
        assertThat(attempts).singleElement().satisfies(attempt ->
                assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.SUCCEEDED));

        List<AuditLogEntry> entries = auditLogEntryRepository.findBySubscriptionId(subscription.getId());
        assertThat(entries).anySatisfy(entry -> {
            assertThat(entry.getOldState()).isEqualTo(proPlan.getCode());
            assertThat(entry.getNewState()).isEqualTo(enterprisePlan.getCode());
        });
    }

    @Test
    void aPendingDowngradeToFreeBecomesFreeOnItsDueDateWithNoChargeAttemptAndNoInvoice() {
        Plan proPlan = planRepository.findByCode("pro").orElseThrow();
        Plan freePlan = planRepository.findByCode("free").orElseThrow();
        LocalDate billingPeriod = LocalDate.of(2026, 7, 12);
        Subscription subscription = seedDueSubscription(proPlan, Instant.parse("2026-07-12T00:00:00Z"), billingPeriod);
        subscription.schedulePlanChange(freePlan, true, Instant.now());
        subscriptionRepository.saveAndFlush(subscription);
        int chargesBefore = paymentGatewayClient.chargeCount();

        billingJobRunner.run();

        Subscription reloaded = subscriptionRepository.findById(subscription.getId()).orElseThrow();
        assertThat(reloaded.getPlan().getId()).isEqualTo(freePlan.getId());
        assertThat(reloaded.getPendingPlanChange()).isNull();
        assertThat(reloaded.getBillingCycleAnchor()).isNull();
        assertThat(reloaded.getDueDate()).isNull();
        assertThat(reloaded.getState()).isEqualTo(SubscriptionState.ACTIVE);

        assertThat(invoiceRepository.findBySubscriptionIdAndBillingPeriod(subscription.getId(), billingPeriod)).isEmpty();
        assertThat(paymentGatewayClient.chargeCount()).isEqualTo(chargesBefore);

        List<AuditLogEntry> entries = auditLogEntryRepository.findBySubscriptionId(subscription.getId());
        assertThat(entries).anySatisfy(entry -> {
            assertThat(entry.getOldState()).isEqualTo(proPlan.getCode());
            assertThat(entry.getNewState()).isEqualTo(freePlan.getCode());
        });
    }

    /**
     * The seeded catalog (V7 migration) only has {@code free} and {@code pro} — a
     * paid-to-paid plan change needs a third, already-available, priced Plan.
     */
    private Plan seedPaidPlan(String code, String name, BigDecimal amount) {
        Plan plan = new Plan(UUID.randomUUID(), code, name);
        planRepository.saveAndFlush(plan);
        priceVersionRepository.saveAndFlush(
                new PriceVersion(UUID.randomUUID(), plan, amount, Instant.parse("2026-01-01T00:00:00Z")));
        return plan;
    }

    private Subscription seedSubscription(Plan plan, LocalDate dueDate) {
        Subscription subscription = Subscription.startPaidImmediately(
                UUID.randomUUID(), seedCustomer("tok_visa"), plan, Instant.now());
        if (dueDate != null) {
            subscription.advanceDueDate(dueDate);
        }
        return subscriptionRepository.saveAndFlush(subscription);
    }

    private Subscription seedDueSubscription(Plan plan, Instant billingCycleAnchor, LocalDate billingPeriod) {
        return seedDueSubscription(plan, billingCycleAnchor, billingPeriod, "tok_visa");
    }

    private Subscription seedDueSubscription(Plan plan, Instant billingCycleAnchor, LocalDate billingPeriod,
                                              String paymentMethodToken) {
        Subscription subscription = Subscription.startPaidImmediately(
                UUID.randomUUID(), seedCustomer(paymentMethodToken), plan, billingCycleAnchor);
        subscription.advanceDueDate(billingPeriod);
        return subscriptionRepository.saveAndFlush(subscription);
    }

    private Subscription seedDueTrialSubscription(Plan plan, LocalDate trialEndDate, String paymentMethodToken) {
        Subscription subscription = Subscription.startTrial(
                UUID.randomUUID(), seedCustomer(paymentMethodToken), plan, Instant.now());
        subscription.advanceDueDate(trialEndDate);
        return subscriptionRepository.saveAndFlush(subscription);
    }

    /**
     * Moves a suspended Subscription's {@code due_date} back to today via the same
     * {@link Subscription#scheduleRetry} mechanism Dunning itself uses, simulating a
     * scheduled retry's day having arrived without the test waiting on the real
     * calendar (day 1/3/7 are computed from the Invoice's real creation instant, which
     * has no clock seam).
     */
    private void forceDueToday(UUID subscriptionId) {
        Subscription subscription = subscriptionRepository.findById(subscriptionId).orElseThrow();
        subscription.scheduleRetry(LocalDate.now(ZoneOffset.UTC));
        subscriptionRepository.saveAndFlush(subscription);
    }

    private Customer seedCustomer(String paymentMethodToken) {
        Customer customer = new Customer(UUID.randomUUID(), "billing-job-it-" + UUID.randomUUID() + "@example.com");
        customer.setPaymentMethodToken(paymentMethodToken);
        return customerRepository.saveAndFlush(customer);
    }
}
