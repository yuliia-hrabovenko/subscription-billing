package com.subscriptionbilling.billingcore.audit;

import com.subscriptionbilling.billingcore.customer.Customer;
import com.subscriptionbilling.billingcore.customer.CustomerRepository;
import com.subscriptionbilling.billingcore.plan.Plan;
import com.subscriptionbilling.billingcore.plan.PlanRepository;
import com.subscriptionbilling.billingcore.subscription.Subscription;
import com.subscriptionbilling.billingcore.subscription.SubscriptionRepository;
import com.subscriptionbilling.billingcore.subscription.SubscriptionState;
import com.subscriptionbilling.billingcore.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AuditLogEntryRepositoryIT extends AbstractPostgresIntegrationTest {

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private PlanRepository planRepository;

    @Autowired
    private SubscriptionRepository subscriptionRepository;

    @Autowired
    private AuditLogEntryRepository auditLogEntryRepository;

    @Test
    void savesAndFetchesAnAuditLogEntry() {
        Customer customer = customerRepository.saveAndFlush(new Customer(UUID.randomUUID(), "alex@example.com"));
        Plan plan = planRepository.findByCode("free").orElseThrow();
        Subscription subscription = subscriptionRepository.saveAndFlush(
                new Subscription(UUID.randomUUID(), customer, plan, SubscriptionState.ACTIVE));

        AuditLogEntry entry = new AuditLogEntry(
                UUID.randomUUID(), subscription.getId(), "CUSTOMER", "STATE_TRANSITION", null, "ACTIVE");

        auditLogEntryRepository.saveAndFlush(entry);

        Optional<AuditLogEntry> found = auditLogEntryRepository.findById(entry.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getSubscriptionId()).isEqualTo(subscription.getId());
        assertThat(found.get().getActor()).isEqualTo("CUSTOMER");
        assertThat(found.get().getNewValue()).isEqualTo("ACTIVE");
        assertThat(found.get().getOccurredAt()).isNotNull();
    }
}
