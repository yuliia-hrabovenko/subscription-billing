package com.subscriptionbilling.billingcore.subscription;

import com.subscriptionbilling.audit.AuditLogEntry;
import com.subscriptionbilling.audit.AuditLogEntryRepository;
import com.subscriptionbilling.billingcore.customer.Customer;
import com.subscriptionbilling.billingcore.customer.CustomerRepository;
import com.subscriptionbilling.billingcore.plan.PlanRepository;
import com.subscriptionbilling.billingcore.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class SubscriptionServiceIT extends AbstractPostgresIntegrationTest {

    @Autowired
    private SubscriptionService subscriptionService;

    @Autowired
    private PlanRepository planRepository;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private SubscriptionRepository subscriptionRepository;

    @Autowired
    private AuditLogEntryRepository auditLogEntryRepository;

    @Test
    void freeSignupPersistsACustomerAnActiveSubscriptionAndExactlyOneAuditLogEntry() {
        UUID freePlanId = planRepository.findByCode("free").orElseThrow().getId();
        String email = "signup-" + UUID.randomUUID() + "@example.com";

        SubscriptionSignupResult result = subscriptionService.signUpForFreePlan(
                new FreeSignupCommand(freePlanId, email, null, "corr-it-1"));

        assertThat(result.state()).isEqualTo(SubscriptionState.ACTIVE);
        assertThat(result.accessToken()).isNotBlank();

        Subscription persisted = subscriptionRepository.findById(result.subscriptionId()).orElseThrow();
        assertThat(persisted.getState()).isEqualTo(SubscriptionState.ACTIVE);
        // Fetched via CustomerRepository directly, not persisted.getCustomer(): that
        // relation is lazy, and this assertion runs outside the service's own
        // transaction, same as a real caller inspecting persisted state after the fact.
        Customer persistedCustomer = customerRepository.findById(persisted.getCustomer().getId()).orElseThrow();
        assertThat(persistedCustomer.getEmail()).isEqualTo(email);

        List<AuditLogEntry> entries = auditLogEntryRepository.findBySubscriptionId(result.subscriptionId());
        assertThat(entries).singleElement().satisfies(entry -> {
            assertThat(entry.getOldState()).isNull();
            assertThat(entry.getNewState()).isEqualTo("ACTIVE");
            assertThat(entry.getCorrelationId()).isEqualTo("corr-it-1");
        });
    }

    @Test
    void getOwnSubscriptionDeniesAnotherCustomersValidTokenAccessToItsSubscription() {
        UUID freePlanId = planRepository.findByCode("free").orElseThrow().getId();
        SubscriptionSignupResult ownerSignup = subscriptionService.signUpForFreePlan(
                new FreeSignupCommand(freePlanId, "owner-" + UUID.randomUUID() + "@example.com", null, "corr-it-2"));
        SubscriptionSignupResult otherSignup = subscriptionService.signUpForFreePlan(
                new FreeSignupCommand(freePlanId, "other-" + UUID.randomUUID() + "@example.com", null, "corr-it-3"));

        Subscription otherSubscription = subscriptionRepository.findById(otherSignup.subscriptionId()).orElseThrow();
        UUID ownerCustomerId = subscriptionRepository.findById(ownerSignup.subscriptionId())
                .orElseThrow().getCustomer().getId();

        assertThat(otherSubscription.getCustomer().getId()).isNotEqualTo(ownerCustomerId);
        assertThatThrownBy(() -> subscriptionService.getOwnSubscription(otherSignup.subscriptionId(), ownerCustomerId))
                .isInstanceOf(SubscriptionAccessDeniedException.class);
    }
}
