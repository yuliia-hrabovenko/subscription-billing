package com.subscriptionbilling.billingcore.subscription;

import com.subscriptionbilling.audit.AuditLogEntry;
import com.subscriptionbilling.audit.AuditLogEntryRepository;
import com.subscriptionbilling.billingcore.customer.Customer;
import com.subscriptionbilling.billingcore.customer.CustomerRepository;
import com.subscriptionbilling.billingcore.plan.Plan;
import com.subscriptionbilling.billingcore.plan.PlanRepository;
import com.subscriptionbilling.billingcore.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
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

        SubscriptionSignupResult result = subscriptionService.signUp(
                new SignupCommand(freePlanId, email, null, false, null, "corr-it-1"));

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
    void trialSignupPersistsATrialingSubscriptionWithATrialEndDateAndNoBillingCycle() {
        UUID proPlanId = planRepository.findByCode("pro").orElseThrow().getId();
        String email = "trialist-" + UUID.randomUUID() + "@example.com";

        SubscriptionSignupResult result = subscriptionService.signUp(
                new SignupCommand(proPlanId, email, null, true, "gw_tok_abc123", "corr-it-2"));

        assertThat(result.state()).isEqualTo(SubscriptionState.TRIALING);
        assertThat(result.trialEndsAt()).isAfter(Instant.now());
        assertThat(result.billingCycleAnchor()).isNull();

        Subscription persisted = subscriptionRepository.findById(result.subscriptionId()).orElseThrow();
        assertThat(persisted.getState()).isEqualTo(SubscriptionState.TRIALING);
        assertThat(persisted.isTrialUsed()).isTrue();
        assertThat(persisted.getTrialEndsAt()).isNotNull();
        assertThat(persisted.getBillingCycleAnchor()).isNull();

        Customer persistedCustomer = customerRepository.findById(persisted.getCustomer().getId()).orElseThrow();
        assertThat(persistedCustomer.getPaymentMethodToken()).isEqualTo("gw_tok_abc123");
    }

    @Test
    void immediatePaidSignupPersistsAnActiveSubscriptionWithABillingCycleAndNoTrialEndDate() {
        UUID proPlanId = planRepository.findByCode("pro").orElseThrow().getId();
        String email = "immediate-" + UUID.randomUUID() + "@example.com";

        SubscriptionSignupResult result = subscriptionService.signUp(
                new SignupCommand(proPlanId, email, null, false, "gw_tok_abc123", "corr-it-3"));

        assertThat(result.state()).isEqualTo(SubscriptionState.ACTIVE);
        assertThat(result.trialEndsAt()).isNull();
        assertThat(result.billingCycleAnchor()).isNotNull();

        Subscription persisted = subscriptionRepository.findById(result.subscriptionId()).orElseThrow();
        assertThat(persisted.isTrialUsed()).isFalse();
        assertThat(persisted.getTrialEndsAt()).isNull();
        assertThat(persisted.getBillingCycleAnchor()).isNotNull();
    }

    @Test
    void aSecondSignupAttemptForACustomerWithAnExistingNonCanceledSubscriptionIsRejected() {
        UUID freePlanId = planRepository.findByCode("free").orElseThrow().getId();
        SubscriptionSignupResult firstSignup = subscriptionService.signUp(
                new SignupCommand(freePlanId, "repeat-" + UUID.randomUUID() + "@example.com", null, false, null, "corr-it-4"));
        UUID customerId = subscriptionRepository.findById(firstSignup.subscriptionId()).orElseThrow().getCustomer().getId();

        assertThatThrownBy(() -> subscriptionService.signUp(
                new SignupCommand(freePlanId, null, customerId, false, null, "corr-it-5")))
                .isInstanceOf(DuplicateSubscriptionException.class);
    }

    @Test
    void aCustomerWithOnlyACanceledSubscriptionCanSignUpAgainAndGetsABrandNewSubscriptionWithFreshTrialEligibility() {
        UUID freePlanId = planRepository.findByCode("free").orElseThrow().getId();
        Plan freePlan = planRepository.findById(freePlanId).orElseThrow();
        Customer customer = customerRepository.saveAndFlush(
                new Customer(UUID.randomUUID(), "resubscriber-" + UUID.randomUUID() + "@example.com"));
        Subscription canceledSubscription = new Subscription(UUID.randomUUID(), customer, freePlan, SubscriptionState.CANCELED);
        subscriptionRepository.saveAndFlush(canceledSubscription);

        SubscriptionSignupResult result = subscriptionService.signUp(
                new SignupCommand(freePlanId, null, customer.getId(), false, null, "corr-it-6"));

        assertThat(result.subscriptionId()).isNotEqualTo(canceledSubscription.getId());
        Subscription newSubscription = subscriptionRepository.findById(result.subscriptionId()).orElseThrow();
        assertThat(newSubscription.getState()).isEqualTo(SubscriptionState.ACTIVE);
        assertThat(newSubscription.isTrialUsed()).isFalse();

        // The old row is untouched — re-subscribing creates a new row, never reactivates one.
        Subscription stillCanceled = subscriptionRepository.findById(canceledSubscription.getId()).orElseThrow();
        assertThat(stillCanceled.getState()).isEqualTo(SubscriptionState.CANCELED);
    }

    @Test
    void getOwnSubscriptionDeniesAnotherCustomersValidTokenAccessToItsSubscription() {
        UUID freePlanId = planRepository.findByCode("free").orElseThrow().getId();
        SubscriptionSignupResult ownerSignup = subscriptionService.signUp(
                new SignupCommand(freePlanId, "owner-" + UUID.randomUUID() + "@example.com", null, false, null, "corr-it-7"));
        SubscriptionSignupResult otherSignup = subscriptionService.signUp(
                new SignupCommand(freePlanId, "other-" + UUID.randomUUID() + "@example.com", null, false, null, "corr-it-8"));

        Subscription otherSubscription = subscriptionRepository.findById(otherSignup.subscriptionId()).orElseThrow();
        UUID ownerCustomerId = subscriptionRepository.findById(ownerSignup.subscriptionId())
                .orElseThrow().getCustomer().getId();

        assertThat(otherSubscription.getCustomer().getId()).isNotEqualTo(ownerCustomerId);
        assertThatThrownBy(() -> subscriptionService.getOwnSubscription(otherSignup.subscriptionId(), ownerCustomerId))
                .isInstanceOf(SubscriptionAccessDeniedException.class);
    }
}
