package com.subscriptionbilling.billingcore.subscription;

import com.subscriptionbilling.audit.ActorType;
import com.subscriptionbilling.audit.AuditLogEntry;
import com.subscriptionbilling.audit.AuditLogEntryRepository;
import com.subscriptionbilling.billingcore.auth.CustomerTokenIssuer;
import com.subscriptionbilling.billingcore.customer.Customer;
import com.subscriptionbilling.billingcore.customer.CustomerRepository;
import com.subscriptionbilling.billingcore.plan.Plan;
import com.subscriptionbilling.billingcore.plan.PlanRepository;
import com.subscriptionbilling.billingcore.plan.PlanSummary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The persistence half of signup, split out of {@link SubscriptionServiceTest} once
 * {@link SubscriptionSignupWriter} took over the database writes (see its Javadoc for
 * why it's a separate bean rather than a private method on {@link SubscriptionService}).
 */
@ExtendWith(MockitoExtension.class)
class SubscriptionSignupWriterTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-08-22T10:15:30Z");

    @Mock
    private CustomerRepository customerRepository;
    @Mock
    private PlanRepository planRepository;
    @Mock
    private SubscriptionRepository subscriptionRepository;
    @Mock
    private AuditLogEntryRepository auditLogEntryRepository;
    @Mock
    private CustomerTokenIssuer tokenIssuer;
    @Mock
    private PasswordEncoder passwordEncoder;

    private final UUID planId = UUID.randomUUID();
    private final Plan freePlan = new Plan(planId, "free", "Free");
    private final Plan proPlan = new Plan(planId, "pro", "Pro");

    private SubscriptionSignupWriter writer() {
        return new SubscriptionSignupWriter(customerRepository, planRepository, subscriptionRepository,
                auditLogEntryRepository, tokenIssuer, passwordEncoder, Clock.fixed(FIXED_NOW, ZoneOffset.UTC));
    }

    @Test
    void freeSignupWithANewCustomerIdCreatesTheCustomerAndAnActiveSubscriptionWithNoPriorState() {
        UUID customerId = UUID.randomUUID();
        when(customerRepository.save(any(Customer.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(planRepository.getReferenceById(planId)).thenReturn(freePlan);
        when(tokenIssuer.issueFor(customerId)).thenReturn("minted-token");
        when(passwordEncoder.encode("password123!")).thenReturn("hashed-password123!");
        PlanSummary plan = new PlanSummary(planId, "free", "Free", new BigDecimal("0.00"));
        SignupCommand command = new SignupCommand(planId, "user@example.com", null, false, null, "password123!", "corr-1");

        SubscriptionSignupResult result = writer().write(command, customerId, plan, true);

        assertThat(result.state()).isEqualTo(SubscriptionState.ACTIVE);
        assertThat(result.planId()).isEqualTo(planId);
        assertThat(result.accessToken()).isEqualTo("minted-token");
        assertThat(result.trialEndsAt()).isNull();
        assertThat(result.billingCycleAnchor()).isNull();

        ArgumentCaptor<Customer> savedCustomer = ArgumentCaptor.forClass(Customer.class);
        verify(customerRepository).save(savedCustomer.capture());
        assertThat(savedCustomer.getValue().getId()).isEqualTo(customerId);
        assertThat(savedCustomer.getValue().getEmail()).isEqualTo("user@example.com");
        assertThat(savedCustomer.getValue().getPasswordHash()).isEqualTo("hashed-password123!");

        ArgumentCaptor<Subscription> savedSubscription = ArgumentCaptor.forClass(Subscription.class);
        verify(subscriptionRepository).save(savedSubscription.capture());
        assertThat(savedSubscription.getValue().getState()).isEqualTo(SubscriptionState.ACTIVE);

        ArgumentCaptor<AuditLogEntry> auditEntry = ArgumentCaptor.forClass(AuditLogEntry.class);
        verify(auditLogEntryRepository).append(auditEntry.capture());
        assertThat(auditEntry.getValue().getActorType()).isEqualTo(ActorType.CUSTOMER);
        assertThat(auditEntry.getValue().getOldState()).isNull();
        assertThat(auditEntry.getValue().getNewState()).isEqualTo("ACTIVE");
        assertThat(auditEntry.getValue().getCorrelationId()).isEqualTo("corr-1");
    }

    @Test
    void freeSignupWithAnExistingCustomerIdReusesThatCustomerInsteadOfMintingANewOne() {
        UUID existingCustomerId = UUID.randomUUID();
        Customer existingCustomer = new Customer(existingCustomerId, "returning@example.com");
        when(customerRepository.findById(existingCustomerId)).thenReturn(Optional.of(existingCustomer));
        when(subscriptionRepository.existsByCustomerIdAndStateNot(existingCustomerId, SubscriptionState.CANCELED))
                .thenReturn(false);
        when(planRepository.getReferenceById(planId)).thenReturn(freePlan);
        when(tokenIssuer.issueFor(existingCustomerId)).thenReturn("minted-token");
        PlanSummary plan = new PlanSummary(planId, "free", "Free", new BigDecimal("0.00"));
        SignupCommand command = new SignupCommand(planId, null, existingCustomerId, false, null, "password123!", "corr-2");

        writer().write(command, existingCustomerId, plan, true);

        verify(customerRepository, never()).save(any());
        verify(tokenIssuer).issueFor(existingCustomerId);
    }

    @Test
    void anExistingCustomerWithAnExistingNonCanceledSubscriptionIsRejectedEvenIfTheCallerDidNotAlreadyCheck() {
        UUID existingCustomerId = UUID.randomUUID();
        Customer existingCustomer = new Customer(existingCustomerId, "returning@example.com");
        when(customerRepository.findById(existingCustomerId)).thenReturn(Optional.of(existingCustomer));
        when(subscriptionRepository.existsByCustomerIdAndStateNot(existingCustomerId, SubscriptionState.CANCELED))
                .thenReturn(true);
        PlanSummary plan = new PlanSummary(planId, "free", "Free", new BigDecimal("0.00"));
        SignupCommand command = new SignupCommand(planId, null, existingCustomerId, false, null, "password123!", "corr-4");

        assertThatThrownBy(() -> writer().write(command, existingCustomerId, plan, true))
                .isInstanceOf(DuplicateSubscriptionException.class);

        verify(subscriptionRepository, never()).save(any());
    }

    @Test
    void trialSignupForAPaidPlanCreatesATrialingSubscriptionWithNoBillingCycle() {
        UUID customerId = UUID.randomUUID();
        when(customerRepository.save(any(Customer.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(planRepository.getReferenceById(planId)).thenReturn(proPlan);
        when(tokenIssuer.issueFor(customerId)).thenReturn("minted-token");
        PlanSummary plan = new PlanSummary(planId, "pro", "Pro", new BigDecimal("19.00"));
        SignupCommand command = new SignupCommand(
                planId, "trialist@example.com", null, true, "pm_abc123", "password123!", "corr-5");

        SubscriptionSignupResult result = writer().write(command, customerId, plan, false);

        assertThat(result.state()).isEqualTo(SubscriptionState.TRIALING);
        assertThat(result.trialEndsAt()).isEqualTo(FIXED_NOW.plus(SubscriptionService.TRIAL_DURATION));
        assertThat(result.billingCycleAnchor()).isNull();

        ArgumentCaptor<Subscription> savedSubscription = ArgumentCaptor.forClass(Subscription.class);
        verify(subscriptionRepository).save(savedSubscription.capture());
        assertThat(savedSubscription.getValue().getState()).isEqualTo(SubscriptionState.TRIALING);
        assertThat(savedSubscription.getValue().isTrialUsed()).isTrue();
        assertThat(savedSubscription.getValue().getBillingCycleAnchor()).isNull();

        ArgumentCaptor<AuditLogEntry> auditEntry = ArgumentCaptor.forClass(AuditLogEntry.class);
        verify(auditLogEntryRepository).append(auditEntry.capture());
        assertThat(auditEntry.getValue().getNewState()).isEqualTo("TRIALING");
        assertThat(auditEntry.getValue().getCorrelationId()).isEqualTo("corr-5");
    }

    @Test
    void immediatePaidSignupCreatesAnActiveSubscriptionWithABillingCycleAnchoredToNow() {
        UUID customerId = UUID.randomUUID();
        when(customerRepository.save(any(Customer.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(planRepository.getReferenceById(planId)).thenReturn(proPlan);
        when(tokenIssuer.issueFor(customerId)).thenReturn("minted-token");
        PlanSummary plan = new PlanSummary(planId, "pro", "Pro", new BigDecimal("19.00"));
        SignupCommand command = new SignupCommand(
                planId, "immediate@example.com", null, false, "pm_abc123", "password123!", "corr-6");

        SubscriptionSignupResult result = writer().write(command, customerId, plan, false);

        assertThat(result.state()).isEqualTo(SubscriptionState.ACTIVE);
        assertThat(result.trialEndsAt()).isNull();
        assertThat(result.billingCycleAnchor()).isEqualTo(FIXED_NOW);

        ArgumentCaptor<Subscription> savedSubscription = ArgumentCaptor.forClass(Subscription.class);
        verify(subscriptionRepository).save(savedSubscription.capture());
        assertThat(savedSubscription.getValue().getState()).isEqualTo(SubscriptionState.ACTIVE);
        assertThat(savedSubscription.getValue().isTrialUsed()).isFalse();
        assertThat(savedSubscription.getValue().getBillingCycleAnchor()).isEqualTo(FIXED_NOW);
    }
}
