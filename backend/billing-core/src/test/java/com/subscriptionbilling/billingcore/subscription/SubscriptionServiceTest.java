package com.subscriptionbilling.billingcore.subscription;

import com.subscriptionbilling.audit.ActorType;
import com.subscriptionbilling.audit.AuditLogEntry;
import com.subscriptionbilling.audit.AuditLogEntryRepository;
import com.subscriptionbilling.billingcore.auth.CustomerTokenIssuer;
import com.subscriptionbilling.billingcore.customer.Customer;
import com.subscriptionbilling.billingcore.customer.CustomerRepository;
import com.subscriptionbilling.billingcore.plan.Plan;
import com.subscriptionbilling.billingcore.plan.PlanCatalogService;
import com.subscriptionbilling.billingcore.plan.PlanRepository;
import com.subscriptionbilling.billingcore.plan.PlanSummary;
import com.subscriptionbilling.billingcore.plan.PlanUnavailableForSignupException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
 * Domain-layer coverage of the three signup edges ({@code [*] -> active} free, {@code
 * [*] -> trialing}, {@code [*] -> active} paid-no-trial) and their rejection paths,
 * independent of the HTTP layer.
 */
@ExtendWith(MockitoExtension.class)
class SubscriptionServiceTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-08-22T10:15:30Z");

    @Mock
    private CustomerRepository customerRepository;
    @Mock
    private PlanRepository planRepository;
    @Mock
    private SubscriptionRepository subscriptionRepository;
    @Mock
    private PlanCatalogService planCatalogService;
    @Mock
    private AuditLogEntryRepository auditLogEntryRepository;
    @Mock
    private CustomerTokenIssuer tokenIssuer;

    private final UUID planId = UUID.randomUUID();
    private final Plan freePlan = new Plan(planId, "free", "Free");
    private final Plan proPlan = new Plan(planId, "pro", "Pro");

    private SubscriptionService service() {
        return new SubscriptionService(customerRepository, planRepository, subscriptionRepository,
                planCatalogService, auditLogEntryRepository, tokenIssuer,
                Clock.fixed(FIXED_NOW, ZoneOffset.UTC));
    }

    @Test
    void freeSignupWithNoExistingCustomerCreatesAnActiveSubscriptionWithNoPriorState() {
        when(planCatalogService.findAvailablePlan(planId))
                .thenReturn(Optional.of(new PlanSummary(planId, "free", "Free", new BigDecimal("0.00"))));
        when(customerRepository.save(any(Customer.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(planRepository.getReferenceById(planId)).thenReturn(freePlan);
        when(tokenIssuer.issueFor(any())).thenReturn("minted-token");

        SubscriptionSignupResult result = service().signUp(
                new SignupCommand(planId, "user@example.com", null, false, null, "corr-1"));

        assertThat(result.state()).isEqualTo(SubscriptionState.ACTIVE);
        assertThat(result.planId()).isEqualTo(planId);
        assertThat(result.accessToken()).isEqualTo("minted-token");
        assertThat(result.trialEndsAt()).isNull();
        assertThat(result.billingCycleAnchor()).isNull();

        ArgumentCaptor<Subscription> savedSubscription = ArgumentCaptor.forClass(Subscription.class);
        verify(subscriptionRepository).save(savedSubscription.capture());
        assertThat(savedSubscription.getValue().getState()).isEqualTo(SubscriptionState.ACTIVE);

        ArgumentCaptor<Customer> savedCustomer = ArgumentCaptor.forClass(Customer.class);
        verify(customerRepository).save(savedCustomer.capture());
        assertThat(savedCustomer.getValue().getEmail()).isEqualTo("user@example.com");

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
        when(planCatalogService.findAvailablePlan(planId))
                .thenReturn(Optional.of(new PlanSummary(planId, "free", "Free", new BigDecimal("0.00"))));
        when(customerRepository.findById(existingCustomerId)).thenReturn(Optional.of(existingCustomer));
        when(subscriptionRepository.existsByCustomerIdAndStateNot(existingCustomerId, SubscriptionState.CANCELED))
                .thenReturn(false);
        when(planRepository.getReferenceById(planId)).thenReturn(freePlan);
        when(tokenIssuer.issueFor(existingCustomerId)).thenReturn("minted-token");

        service().signUp(new SignupCommand(planId, null, existingCustomerId, false, null, "corr-2"));

        verify(customerRepository, never()).save(any());
        verify(tokenIssuer).issueFor(existingCustomerId);
    }

    @Test
    void signupForAPlanThatIsNotAvailableIsRejected() {
        when(planCatalogService.findAvailablePlan(planId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().signUp(new SignupCommand(planId, "user@example.com", null, false, null, "corr-3")))
                .isInstanceOf(PlanUnavailableForSignupException.class);

        verify(subscriptionRepository, never()).save(any());
    }

    @Test
    void aCustomerWithAnExistingNonCanceledSubscriptionIsRejectedFromSigningUpAgain() {
        UUID existingCustomerId = UUID.randomUUID();
        Customer existingCustomer = new Customer(existingCustomerId, "returning@example.com");
        when(planCatalogService.findAvailablePlan(planId))
                .thenReturn(Optional.of(new PlanSummary(planId, "free", "Free", new BigDecimal("0.00"))));
        when(customerRepository.findById(existingCustomerId)).thenReturn(Optional.of(existingCustomer));
        when(subscriptionRepository.existsByCustomerIdAndStateNot(existingCustomerId, SubscriptionState.CANCELED))
                .thenReturn(true);

        assertThatThrownBy(() -> service().signUp(
                new SignupCommand(planId, null, existingCustomerId, false, null, "corr-4")))
                .isInstanceOf(DuplicateSubscriptionException.class);

        verify(subscriptionRepository, never()).save(any());
    }

    @Test
    void trialSignupForAPaidPlanCreatesATrialingSubscriptionWithNoBillingCycle() {
        when(planCatalogService.findAvailablePlan(planId))
                .thenReturn(Optional.of(new PlanSummary(planId, "pro", "Pro", new BigDecimal("19.00"))));
        when(customerRepository.save(any(Customer.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(planRepository.getReferenceById(planId)).thenReturn(proPlan);
        when(tokenIssuer.issueFor(any())).thenReturn("minted-token");

        SubscriptionSignupResult result = service().signUp(
                new SignupCommand(planId, "trialist@example.com", null, true, "gw_tok_abc123", "corr-5"));

        assertThat(result.state()).isEqualTo(SubscriptionState.TRIALING);
        assertThat(result.trialEndsAt()).isEqualTo(FIXED_NOW.plus(SubscriptionService.TRIAL_DURATION));
        assertThat(result.billingCycleAnchor()).isNull();

        ArgumentCaptor<Subscription> savedSubscription = ArgumentCaptor.forClass(Subscription.class);
        verify(subscriptionRepository).save(savedSubscription.capture());
        assertThat(savedSubscription.getValue().getState()).isEqualTo(SubscriptionState.TRIALING);
        assertThat(savedSubscription.getValue().isTrialUsed()).isTrue();
        assertThat(savedSubscription.getValue().getBillingCycleAnchor()).isNull();

        ArgumentCaptor<Customer> savedCustomer = ArgumentCaptor.forClass(Customer.class);
        verify(customerRepository).save(savedCustomer.capture());
        assertThat(savedCustomer.getValue().getPaymentMethodToken()).isEqualTo("gw_tok_abc123");

        ArgumentCaptor<AuditLogEntry> auditEntry = ArgumentCaptor.forClass(AuditLogEntry.class);
        verify(auditLogEntryRepository).append(auditEntry.capture());
        assertThat(auditEntry.getValue().getNewState()).isEqualTo("TRIALING");
    }

    @Test
    void immediatePaidSignupCreatesAnActiveSubscriptionWithABillingCycleAnchoredToNow() {
        when(planCatalogService.findAvailablePlan(planId))
                .thenReturn(Optional.of(new PlanSummary(planId, "pro", "Pro", new BigDecimal("19.00"))));
        when(customerRepository.save(any(Customer.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(planRepository.getReferenceById(planId)).thenReturn(proPlan);
        when(tokenIssuer.issueFor(any())).thenReturn("minted-token");

        SubscriptionSignupResult result = service().signUp(
                new SignupCommand(planId, "immediate@example.com", null, false, "gw_tok_abc123", "corr-6"));

        assertThat(result.state()).isEqualTo(SubscriptionState.ACTIVE);
        assertThat(result.trialEndsAt()).isNull();
        assertThat(result.billingCycleAnchor()).isEqualTo(FIXED_NOW);

        ArgumentCaptor<Subscription> savedSubscription = ArgumentCaptor.forClass(Subscription.class);
        verify(subscriptionRepository).save(savedSubscription.capture());
        assertThat(savedSubscription.getValue().getState()).isEqualTo(SubscriptionState.ACTIVE);
        assertThat(savedSubscription.getValue().isTrialUsed()).isFalse();
        assertThat(savedSubscription.getValue().getBillingCycleAnchor()).isEqualTo(FIXED_NOW);

        ArgumentCaptor<AuditLogEntry> auditEntry = ArgumentCaptor.forClass(AuditLogEntry.class);
        verify(auditLogEntryRepository).append(auditEntry.capture());
        assertThat(auditEntry.getValue().getNewState()).isEqualTo("ACTIVE");
    }

    @Test
    void trialSignupWithoutAPaymentMethodTokenIsRejected() {
        when(planCatalogService.findAvailablePlan(planId))
                .thenReturn(Optional.of(new PlanSummary(planId, "pro", "Pro", new BigDecimal("19.00"))));

        assertThatThrownBy(() -> service().signUp(
                new SignupCommand(planId, "trialist@example.com", null, true, null, "corr-7")))
                .isInstanceOf(PaymentMethodRequiredException.class);

        verify(subscriptionRepository, never()).save(any());
    }

    @Test
    void immediatePaidSignupWithoutAPaymentMethodTokenIsRejected() {
        when(planCatalogService.findAvailablePlan(planId))
                .thenReturn(Optional.of(new PlanSummary(planId, "pro", "Pro", new BigDecimal("19.00"))));

        assertThatThrownBy(() -> service().signUp(
                new SignupCommand(planId, "immediate@example.com", null, false, "   ", "corr-8")))
                .isInstanceOf(PaymentMethodRequiredException.class);

        verify(subscriptionRepository, never()).save(any());
    }

    @Test
    void getOwnSubscriptionReturnsItWhenTheAuthenticatedCustomerIsTheOwner() {
        UUID subscriptionId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        Customer owner = new Customer(customerId, "user@example.com");
        Subscription subscription = new Subscription(subscriptionId, owner, freePlan, SubscriptionState.ACTIVE);
        when(subscriptionRepository.findById(subscriptionId)).thenReturn(Optional.of(subscription));

        SubscriptionView view = service().getOwnSubscription(subscriptionId, customerId);

        assertThat(view.id()).isEqualTo(subscriptionId);
        assertThat(view.state()).isEqualTo(SubscriptionState.ACTIVE);
        assertThat(view.plan().code()).isEqualTo("free");
        assertThat(view.pendingPlanChange()).isNull();
        assertThat(view.trialEndsAt()).isNull();
        assertThat(view.billingCycleAnchor()).isNull();
    }

    @Test
    void getOwnSubscriptionDeniesAccessWhenTheAuthenticatedCustomerIsNotTheOwner() {
        UUID subscriptionId = UUID.randomUUID();
        Customer owner = new Customer(UUID.randomUUID(), "owner@example.com");
        Subscription subscription = new Subscription(subscriptionId, owner, freePlan, SubscriptionState.ACTIVE);
        when(subscriptionRepository.findById(subscriptionId)).thenReturn(Optional.of(subscription));

        assertThatThrownBy(() -> service().getOwnSubscription(subscriptionId, UUID.randomUUID()))
                .isInstanceOf(SubscriptionAccessDeniedException.class);
    }

    @Test
    void getOwnSubscriptionDeniesAccessWhenTheSubscriptionDoesNotExistAtAll() {
        UUID subscriptionId = UUID.randomUUID();
        when(subscriptionRepository.findById(subscriptionId)).thenReturn(Optional.empty());

        // Same exception as the wrong-owner case above: see SubscriptionAccessDeniedException's
        // Javadoc for why the two must be indistinguishable to the caller.
        assertThatThrownBy(() -> service().getOwnSubscription(subscriptionId, UUID.randomUUID()))
                .isInstanceOf(SubscriptionAccessDeniedException.class);
    }
}
