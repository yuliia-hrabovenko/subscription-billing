package com.subscriptionbilling.billingcore.subscription;

import com.subscriptionbilling.billingcore.customer.Customer;
import com.subscriptionbilling.billingcore.customer.CustomerRepository;
import com.subscriptionbilling.billingcore.plan.Plan;
import com.subscriptionbilling.billingcore.plan.PlanRepository;
import com.subscriptionbilling.billingcore.support.AbstractPostgresIntegrationTest;
import com.subscriptionbilling.billingjob.BillingJobRunner;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves {@link BillingJobRunner#run()}'s selection is correct against real persisted
 * Subscriptions, through {@link DueSubscriptionsAdapter} and a real Postgres — no
 * charging is involved yet, and no scheduler/cron infrastructure is involved: the
 * runner's entry point is invoked directly.
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
    private SubscriptionRepository subscriptionRepository;

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

    private Subscription seedSubscription(Plan plan, LocalDate dueDate) {
        Customer customer = customerRepository.saveAndFlush(
                new Customer(UUID.randomUUID(), "due-scan-" + UUID.randomUUID() + "@example.com"));
        Subscription subscription = new Subscription(
                UUID.randomUUID(), customer, plan, SubscriptionState.ACTIVE, null, dueDate);
        return subscriptionRepository.saveAndFlush(subscription);
    }
}
