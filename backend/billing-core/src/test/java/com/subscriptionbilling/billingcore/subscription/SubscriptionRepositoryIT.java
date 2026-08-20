package com.subscriptionbilling.billingcore.subscription;

import com.subscriptionbilling.billingcore.customer.Customer;
import com.subscriptionbilling.billingcore.customer.CustomerRepository;
import com.subscriptionbilling.billingcore.plan.Plan;
import com.subscriptionbilling.billingcore.plan.PlanRepository;
import com.subscriptionbilling.billingcore.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SubscriptionRepositoryIT extends AbstractPostgresIntegrationTest {

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private PlanRepository planRepository;

    @Autowired
    private SubscriptionRepository subscriptionRepository;

    @Test
    void savesAndFetchesASubscription() {
        Customer customer = customerRepository.saveAndFlush(new Customer(UUID.randomUUID(), "sam@example.com"));
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
}
