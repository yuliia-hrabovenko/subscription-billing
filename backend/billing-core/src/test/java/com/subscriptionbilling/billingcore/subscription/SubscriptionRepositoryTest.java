package com.subscriptionbilling.billingcore.subscription;

import com.subscriptionbilling.billingcore.customer.Customer;
import com.subscriptionbilling.billingcore.customer.CustomerRepository;
import com.subscriptionbilling.billingcore.plan.Plan;
import com.subscriptionbilling.billingcore.plan.PlanRepository;
import com.subscriptionbilling.billingcore.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class SubscriptionRepositoryTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private PlanRepository planRepository;

    @Autowired
    private SubscriptionRepository subscriptionRepository;

    @Test
    void savesAndFetchesASubscription() {
        Customer customer = customerRepository.saveAndFlush(new Customer(UUID.randomUUID(), "customer@example.com"));
        Plan plan = planRepository.findByCode("free").orElseThrow();
        Subscription subscription = new Subscription(UUID.randomUUID(), customer, plan, SubscriptionState.ACTIVE);

        subscriptionRepository.saveAndFlush(subscription);

        Optional<Subscription> found = subscriptionRepository.findById(subscription.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getState()).isEqualTo(SubscriptionState.ACTIVE);
        assertThat(found.get().getCustomer().getId()).isEqualTo(customer.getId());
        assertThat(found.get().getPlan().getId()).isEqualTo(plan.getId());
        assertThat(found.get().getPendingPlanChange()).isNull();
        assertThat(found.get().isTrialUsed()).isFalse();
    }

    @Test
    void findTrialsEndingSoonIdsSelectsATrialingSubscriptionWhoseTrialEndsOnOrBeforeTheCutoff() {
        Instant leadTimeCutoff = Instant.parse("2026-09-01T00:00:00Z");
        Subscription endingSoon = saveTrialingSubscription(leadTimeCutoff.minusSeconds(1));

        List<UUID> selected = subscriptionRepository.findTrialsEndingSoonIds(SubscriptionState.TRIALING, leadTimeCutoff);

        assertThat(selected).contains(endingSoon.getId());
    }

    @Test
    void findTrialsEndingSoonIdsExcludesATrialTooFarInTheFutureToBeWithinTheLeadTime() {
        Instant leadTimeCutoff = Instant.parse("2026-09-01T00:00:00Z");
        Subscription tooEarly = saveTrialingSubscription(leadTimeCutoff.plusSeconds(1));

        List<UUID> selected = subscriptionRepository.findTrialsEndingSoonIds(SubscriptionState.TRIALING, leadTimeCutoff);

        assertThat(selected).doesNotContain(tooEarly.getId());
    }

    @Test
    void findTrialsEndingSoonIdsExcludesASubscriptionAlreadyNotified() {
        Instant leadTimeCutoff = Instant.parse("2026-09-01T00:00:00Z");
        Subscription alreadyNotified = saveTrialingSubscription(leadTimeCutoff.minusSeconds(1));
        alreadyNotified.markTrialEndingSoonNotified(Instant.parse("2026-08-25T00:00:00Z"));
        subscriptionRepository.saveAndFlush(alreadyNotified);

        List<UUID> selected = subscriptionRepository.findTrialsEndingSoonIds(SubscriptionState.TRIALING, leadTimeCutoff);

        assertThat(selected).doesNotContain(alreadyNotified.getId());
    }

    @Test
    void findTrialsEndingSoonIdsExcludesANonTrialingSubscriptionEvenWithATrialEndsAtWithinTheWindow() {
        // A converted or canceled Subscription no longer carries state TRIALING -- this
        // proves the query relies on that alone, with no separate "already converted"
        // check needed.
        Instant leadTimeCutoff = Instant.parse("2026-09-01T00:00:00Z");
        Customer customer = customerRepository.saveAndFlush(new Customer(UUID.randomUUID(), "active@example.com"));
        Plan plan = planRepository.findByCode("pro").orElseThrow();
        Subscription active = Subscription.startPaidImmediately(UUID.randomUUID(), customer, plan, Instant.now());
        subscriptionRepository.saveAndFlush(active);

        List<UUID> selected = subscriptionRepository.findTrialsEndingSoonIds(SubscriptionState.TRIALING, leadTimeCutoff);

        assertThat(selected).doesNotContain(active.getId());
    }

    private Subscription saveTrialingSubscription(Instant trialEndsAt) {
        Customer customer = customerRepository.saveAndFlush(new Customer(UUID.randomUUID(), "trialist-" + UUID.randomUUID() + "@example.com"));
        Plan plan = planRepository.findByCode("pro").orElseThrow();
        Subscription subscription = Subscription.startTrial(UUID.randomUUID(), customer, plan, trialEndsAt);
        return subscriptionRepository.saveAndFlush(subscription);
    }
}
