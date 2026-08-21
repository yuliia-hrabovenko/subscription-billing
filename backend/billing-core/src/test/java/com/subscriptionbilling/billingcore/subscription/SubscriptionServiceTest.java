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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Domain-layer coverage of the {@code [*] -> active} edge (free signup), independent of
 * the HTTP layer.
 */
@ExtendWith(MockitoExtension.class)
class SubscriptionServiceTest {

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

    private SubscriptionService service() {
        return new SubscriptionService(customerRepository, planRepository, subscriptionRepository,
                planCatalogService, auditLogEntryRepository, tokenIssuer);
    }

    @Test
    void freeSignupWithNoExistingCustomerCreatesAnActiveSubscriptionWithNoPriorState() {
        when(planCatalogService.findAvailablePlan(planId))
                .thenReturn(Optional.of(new PlanSummary(planId, "free", "Free", new BigDecimal("0.00"))));
        when(customerRepository.save(any(Customer.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(planRepository.getReferenceById(planId)).thenReturn(freePlan);
        when(tokenIssuer.issueFor(any())).thenReturn("minted-token");

        SubscriptionSignupResult result = service().signUpForFreePlan(
                new FreeSignupCommand(planId, "user@example.com", null, "corr-1"));

        assertThat(result.state()).isEqualTo(SubscriptionState.ACTIVE);
        assertThat(result.planId()).isEqualTo(planId);
        assertThat(result.accessToken()).isEqualTo("minted-token");

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
        when(planRepository.getReferenceById(planId)).thenReturn(freePlan);
        when(tokenIssuer.issueFor(existingCustomerId)).thenReturn("minted-token");

        service().signUpForFreePlan(new FreeSignupCommand(planId, null, existingCustomerId, "corr-2"));

        verify(customerRepository, never()).save(any());
        verify(tokenIssuer).issueFor(existingCustomerId);
    }

    @Test
    void signupForAPlanThatIsNotAvailableIsRejected() {
        when(planCatalogService.findAvailablePlan(planId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().signUpForFreePlan(new FreeSignupCommand(planId, "user@example.com", null, "corr-3")))
                .isInstanceOf(PlanUnavailableForSignupException.class);

        verify(subscriptionRepository, never()).save(any());
    }

    @Test
    void signupForAPaidPlanIsRejectedBecauseOnlyFreeSignupIsImplementedSoFar() {
        when(planCatalogService.findAvailablePlan(planId))
                .thenReturn(Optional.of(new PlanSummary(planId, "pro", "Pro", new BigDecimal("19.00"))));

        assertThatThrownBy(() -> service().signUpForFreePlan(new FreeSignupCommand(planId, "user@example.com", null, "corr-4")))
                .isInstanceOf(UnsupportedSignupPlanException.class);

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
