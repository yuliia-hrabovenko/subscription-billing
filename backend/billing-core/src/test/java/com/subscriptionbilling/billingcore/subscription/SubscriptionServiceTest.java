package com.subscriptionbilling.billingcore.subscription;

import com.subscriptionbilling.audit.ActorType;
import com.subscriptionbilling.audit.AuditLogEntry;
import com.subscriptionbilling.audit.AuditLogEntryRepository;
import com.subscriptionbilling.billingcore.auth.CustomerTokenIssuer;
import com.subscriptionbilling.billingcore.customer.Customer;
import com.subscriptionbilling.billingcore.customer.CustomerRepository;
import com.subscriptionbilling.billingcore.idempotency.IdempotencyService;
import com.subscriptionbilling.billingcore.plan.Plan;
import com.subscriptionbilling.billingcore.plan.PlanCatalogService;
import com.subscriptionbilling.billingcore.plan.PlanRepository;
import com.subscriptionbilling.billingcore.plan.PlanSummary;
import com.subscriptionbilling.billingcore.plan.PlanUnavailableForSignupException;
import com.subscriptionbilling.billingjob.charge.ChargeableSubscription;
import com.subscriptionbilling.billingjob.charge.ChargeableSubscriptionPort;
import com.subscriptionbilling.billingjob.dunning.DunningRetryCharge;
import com.subscriptionbilling.billingjob.dunning.DunningRetryChargeResult;
import com.subscriptionbilling.billingjob.dunning.DunningRetryOutcome;
import com.subscriptionbilling.billingjob.invoicing.InvoiceCancellationPort;
import com.subscriptionbilling.notifications.outbox.OutboxEvent;
import com.subscriptionbilling.notifications.outbox.OutboxEventRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Domain-layer coverage of {@link SubscriptionService}'s use cases: the three signup
 * edges ({@code [*] -> active} free, {@code [*] -> trialing}, {@code [*] -> active}
 * paid-no-trial), cancel/undo-cancel and their rejection paths, ownership enforcement,
 * and idempotency-key deduplication — independent of the HTTP layer. See {@link
 * SubscriptionTest} for the state machine's own edges in isolation, without the
 * repository/audit/idempotency orchestration this class covers.
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
    @Mock
    private IdempotencyService idempotencyService;
    @Mock
    private ChargeableSubscriptionPort chargeableSubscriptionPort;
    @Mock
    private DunningRetryCharge dunningRetryCharge;
    @Mock
    private InvoiceCancellationPort invoiceCancellationPort;
    @Mock
    private OutboxEventRepository outboxEventRepository;

    private final UUID planId = UUID.randomUUID();
    private final Plan freePlan = new Plan(planId, "free", "Free");
    private final Plan proPlan = new Plan(planId, "pro", "Pro");

    private SubscriptionService service() {
        return new SubscriptionService(customerRepository, planRepository, subscriptionRepository,
                planCatalogService, auditLogEntryRepository, tokenIssuer, idempotencyService,
                chargeableSubscriptionPort, dunningRetryCharge, invoiceCancellationPort, outboxEventRepository,
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

    @Test
    void cancelFromTrialingTransitionsImmediatelyToCanceledAndWritesAnAuditLogEntry() {
        UUID customerId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        Subscription subscription = Subscription.startTrial(
                subscriptionId, new Customer(customerId, "trialist@example.com"), proPlan, FIXED_NOW);
        when(subscriptionRepository.findById(subscriptionId)).thenReturn(Optional.of(subscription));

        SubscriptionView view = service().cancel(subscriptionId, customerId, null, "corr-cancel-1");

        assertThat(view.state()).isEqualTo(SubscriptionState.CANCELED);

        ArgumentCaptor<AuditLogEntry> auditEntry = ArgumentCaptor.forClass(AuditLogEntry.class);
        verify(auditLogEntryRepository).append(auditEntry.capture());
        assertThat(auditEntry.getValue().getOldState()).isEqualTo("TRIALING");
        assertThat(auditEntry.getValue().getNewState()).isEqualTo("CANCELED");
        assertThat(auditEntry.getValue().getCorrelationId()).isEqualTo("corr-cancel-1");

        ArgumentCaptor<OutboxEvent> outboxEvent = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(outboxEvent.capture());
        assertThat(outboxEvent.getValue().getEventType()).isEqualTo("CANCELLATION_CONFIRMED");
        assertThat(outboxEvent.getValue().getPayload()).contains(subscriptionId.toString()).contains(customerId.toString());
    }

    @Test
    void cancelFromActiveWithABillingCycleDefersToPendingCancellationAndWritesAnAuditLogEntry() {
        UUID customerId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        Subscription subscription = Subscription.startPaidImmediately(
                subscriptionId, new Customer(customerId, "active@example.com"), proPlan, FIXED_NOW);
        when(subscriptionRepository.findById(subscriptionId)).thenReturn(Optional.of(subscription));

        SubscriptionView view = service().cancel(subscriptionId, customerId, null, "corr-cancel-2");

        assertThat(view.state()).isEqualTo(SubscriptionState.PENDING_CANCELLATION);
        assertThat(view.plan().code()).isEqualTo("pro");

        ArgumentCaptor<AuditLogEntry> auditEntry = ArgumentCaptor.forClass(AuditLogEntry.class);
        verify(auditLogEntryRepository).append(auditEntry.capture());
        assertThat(auditEntry.getValue().getOldState()).isEqualTo("ACTIVE");
        assertThat(auditEntry.getValue().getNewState()).isEqualTo("PENDING_CANCELLATION");

        // The deferred branch still confirms the request was recorded, not that the
        // Subscription has reached canceled.
        verify(outboxEventRepository).save(any(OutboxEvent.class));
    }

    @Test
    void cancelFromActiveWithNoBillingCycleTransitionsImmediatelyToCanceled() {
        // A free-Plan Subscription is ACTIVE with no Billing Cycle (billingCycleAnchor
        // null) — there's no paid period to defer to, so it cancels immediately instead
        // of stranding in pending_cancellation forever.
        UUID customerId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        Subscription subscription = new Subscription(
                subscriptionId, new Customer(customerId, "free-active@example.com"), freePlan, SubscriptionState.ACTIVE);
        when(subscriptionRepository.findById(subscriptionId)).thenReturn(Optional.of(subscription));

        SubscriptionView view = service().cancel(subscriptionId, customerId, null, "corr-cancel-2b");

        assertThat(view.state()).isEqualTo(SubscriptionState.CANCELED);

        ArgumentCaptor<AuditLogEntry> auditEntry = ArgumentCaptor.forClass(AuditLogEntry.class);
        verify(auditLogEntryRepository).append(auditEntry.capture());
        assertThat(auditEntry.getValue().getOldState()).isEqualTo("ACTIVE");
        assertThat(auditEntry.getValue().getNewState()).isEqualTo("CANCELED");
        verify(outboxEventRepository).save(any(OutboxEvent.class));
    }

    @Test
    void cancelFromSuspendedTransitionsImmediatelyToCanceled() {
        UUID customerId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        Subscription subscription = new Subscription(
                subscriptionId, new Customer(customerId, "suspended@example.com"), proPlan, SubscriptionState.SUSPENDED);
        when(subscriptionRepository.findById(subscriptionId)).thenReturn(Optional.of(subscription));

        SubscriptionView view = service().cancel(subscriptionId, customerId, null, "corr-cancel-3");

        assertThat(view.state()).isEqualTo(SubscriptionState.CANCELED);
        // No dunningBillingPeriod on this hand-seeded fixture (only suspend() sets one) --
        // there's no still-open Invoice to fail.
        verify(invoiceCancellationPort, never()).failStillOpenInvoice(any(), any());
        verify(outboxEventRepository).save(any(OutboxEvent.class));
    }

    @Test
    void cancelFromSuspendedFailsTheStillOpenInvoiceForTheDunningBillingPeriod() {
        UUID customerId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        Subscription subscription = Subscription.startPaidImmediately(
                subscriptionId, new Customer(customerId, "suspended-real@example.com"), proPlan, FIXED_NOW);
        subscription.suspend(LocalDate.of(2026, 8, 1));
        when(subscriptionRepository.findById(subscriptionId)).thenReturn(Optional.of(subscription));

        service().cancel(subscriptionId, customerId, null, "corr-cancel-3b");

        verify(invoiceCancellationPort).failStillOpenInvoice(subscriptionId, LocalDate.of(2026, 8, 1));
    }

    @Test
    void cancelFromActiveOrTrialingNeverFailsAnInvoiceSinceNeitherHasADunningBillingPeriod() {
        UUID customerId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        Subscription subscription = Subscription.startTrial(
                subscriptionId, new Customer(customerId, "trialist@example.com"), proPlan, FIXED_NOW);
        when(subscriptionRepository.findById(subscriptionId)).thenReturn(Optional.of(subscription));

        service().cancel(subscriptionId, customerId, null, "corr-cancel-3c");

        verifyNoInteractions(invoiceCancellationPort);
    }

    @Test
    void cancelOnAnAlreadyPendingCancellationSubscriptionIsRejected() {
        UUID customerId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        Subscription subscription = new Subscription(subscriptionId, new Customer(customerId, "pending@example.com"),
                proPlan, SubscriptionState.PENDING_CANCELLATION);
        when(subscriptionRepository.findById(subscriptionId)).thenReturn(Optional.of(subscription));

        assertThatThrownBy(() -> service().cancel(subscriptionId, customerId, null, "corr-cancel-4"))
                .isInstanceOf(SubscriptionAlreadyPendingCancellationException.class);

        verify(auditLogEntryRepository, never()).append(any());
        verifyNoInteractions(outboxEventRepository);
    }

    @Test
    void cancelOnAnAlreadyCanceledSubscriptionIsRejected() {
        UUID customerId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        Subscription subscription = new Subscription(
                subscriptionId, new Customer(customerId, "canceled@example.com"), proPlan, SubscriptionState.CANCELED);
        when(subscriptionRepository.findById(subscriptionId)).thenReturn(Optional.of(subscription));

        assertThatThrownBy(() -> service().cancel(subscriptionId, customerId, null, "corr-cancel-5"))
                .isInstanceOf(SubscriptionAlreadyCanceledException.class);

        verify(auditLogEntryRepository, never()).append(any());
        verifyNoInteractions(outboxEventRepository);
    }

    @Test
    void cancelForAWrongOwnerIsRejectedWithAccessDenied() {
        UUID subscriptionId = UUID.randomUUID();
        Subscription subscription = new Subscription(
                subscriptionId, new Customer(UUID.randomUUID(), "owner@example.com"), proPlan, SubscriptionState.ACTIVE);
        when(subscriptionRepository.findById(subscriptionId)).thenReturn(Optional.of(subscription));

        assertThatThrownBy(() -> service().cancel(subscriptionId, UUID.randomUUID(), null, "corr-cancel-6"))
                .isInstanceOf(SubscriptionAccessDeniedException.class);

        verify(auditLogEntryRepository, never()).append(any());
        verifyNoInteractions(outboxEventRepository);
    }

    @Test
    void aRepeatedCancelWithTheSameIdempotencyKeyDoesNotReapplyOrWriteASecondAuditEntry() {
        UUID customerId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        Subscription subscription = new Subscription(
                subscriptionId, new Customer(customerId, "retry@example.com"), proPlan, SubscriptionState.ACTIVE);
        when(subscriptionRepository.findById(subscriptionId)).thenReturn(Optional.of(subscription));
        when(idempotencyService.recordIfNew(customerId, SubscriptionService.CANCEL_OPERATION, "key-1")).thenReturn(false);

        SubscriptionView view = service().cancel(subscriptionId, customerId, "key-1", "corr-cancel-7");

        // The Subscription is untouched: still ACTIVE, not PENDING_CANCELLATION — the
        // transition was never re-run for this deduplicated retry.
        assertThat(view.state()).isEqualTo(SubscriptionState.ACTIVE);
        verify(auditLogEntryRepository, never()).append(any());
        // Never a second, separate CANCELLATION_CONFIRMED write for a deduplicated retry.
        verifyNoInteractions(outboxEventRepository);
    }

    @Test
    void cancelWithAnIdempotencyKeyThatEndsUpRejectedReleasesTheKeyInsteadOfBurningIt() {
        // The key was recorded (recordIfNew returned true, meaning it committed
        // independently) before cancel() ran and threw — since the transition never
        // actually applied, the key must be released, or a legitimate retry of this
        // same failing request would find the key already "used" and be wrongly
        // short-circuited into a fabricated success instead of the same rejection.
        UUID customerId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        Subscription subscription = new Subscription(
                subscriptionId, new Customer(customerId, "rejected@example.com"), proPlan, SubscriptionState.CANCELED);
        when(subscriptionRepository.findById(subscriptionId)).thenReturn(Optional.of(subscription));
        when(idempotencyService.recordIfNew(customerId, SubscriptionService.CANCEL_OPERATION, "key-3")).thenReturn(true);

        assertThatThrownBy(() -> service().cancel(subscriptionId, customerId, "key-3", "corr-cancel-8"))
                .isInstanceOf(SubscriptionAlreadyCanceledException.class);

        verify(idempotencyService).release(customerId, SubscriptionService.CANCEL_OPERATION, "key-3");
        verify(auditLogEntryRepository, never()).append(any());
        verifyNoInteractions(outboxEventRepository);
    }

    @Test
    void cancelReleasesTheIdempotencyKeyWhenTheFlushFailsWithOptimisticLocking() {
        // Unlike a domain-layer rejection (Subscription.cancel() throwing before any
        // mutation), an optimistic-lock failure only surfaces once the mutated
        // Subscription is flushed — proving the release logic covers failures raised by
        // the explicit saveAndFlush call, not just ones raised by the transition itself.
        UUID customerId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        Subscription subscription = new Subscription(
                subscriptionId, new Customer(customerId, "optlock@example.com"), proPlan, SubscriptionState.ACTIVE);
        when(subscriptionRepository.findById(subscriptionId)).thenReturn(Optional.of(subscription));
        when(idempotencyService.recordIfNew(customerId, SubscriptionService.CANCEL_OPERATION, "key-4")).thenReturn(true);
        when(subscriptionRepository.saveAndFlush(subscription))
                .thenThrow(new ObjectOptimisticLockingFailureException(Subscription.class, subscriptionId));

        assertThatThrownBy(() -> service().cancel(subscriptionId, customerId, "key-4", "corr-cancel-10"))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        verify(idempotencyService).release(customerId, SubscriptionService.CANCEL_OPERATION, "key-4");
        verify(auditLogEntryRepository, never()).append(any());
        // The transition ran in memory before the flush failed, but the whole method
        // throws before ever reaching the outbox write -- no event for a failed flush.
        verifyNoInteractions(outboxEventRepository);
    }

    @Test
    void cancelWithNoIdempotencyKeyNeverTouchesIdempotencyServiceEvenOnRejection() {
        UUID customerId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        Subscription subscription = new Subscription(
                subscriptionId, new Customer(customerId, "norejeckey@example.com"), proPlan, SubscriptionState.CANCELED);
        when(subscriptionRepository.findById(subscriptionId)).thenReturn(Optional.of(subscription));

        assertThatThrownBy(() -> service().cancel(subscriptionId, customerId, null, "corr-cancel-9"))
                .isInstanceOf(SubscriptionAlreadyCanceledException.class);

        verify(idempotencyService, never()).release(any(), any(), any());
    }

    @Test
    void undoCancelFromPendingCancellationRestoresActiveAndWritesAnAuditLogEntry() {
        UUID customerId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        Subscription subscription = new Subscription(subscriptionId, new Customer(customerId, "pending@example.com"),
                proPlan, SubscriptionState.PENDING_CANCELLATION);
        when(subscriptionRepository.findById(subscriptionId)).thenReturn(Optional.of(subscription));

        SubscriptionView view = service().undoCancel(subscriptionId, customerId, null, "corr-undo-1");

        assertThat(view.state()).isEqualTo(SubscriptionState.ACTIVE);

        ArgumentCaptor<AuditLogEntry> auditEntry = ArgumentCaptor.forClass(AuditLogEntry.class);
        verify(auditLogEntryRepository).append(auditEntry.capture());
        assertThat(auditEntry.getValue().getOldState()).isEqualTo("PENDING_CANCELLATION");
        assertThat(auditEntry.getValue().getNewState()).isEqualTo("ACTIVE");
        // Undoing a cancellation is not itself a cancellation-confirmed trigger.
        verifyNoInteractions(outboxEventRepository);
    }

    @Test
    void undoCancelFromAnyOtherStateIsRejected() {
        UUID customerId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        Subscription subscription = new Subscription(
                subscriptionId, new Customer(customerId, "active@example.com"), proPlan, SubscriptionState.ACTIVE);
        when(subscriptionRepository.findById(subscriptionId)).thenReturn(Optional.of(subscription));

        assertThatThrownBy(() -> service().undoCancel(subscriptionId, customerId, null, "corr-undo-2"))
                .isInstanceOf(SubscriptionNotPendingCancellationException.class);

        verify(auditLogEntryRepository, never()).append(any());
    }

    @Test
    void undoCancelForAWrongOwnerIsRejectedWithAccessDenied() {
        UUID subscriptionId = UUID.randomUUID();
        Subscription subscription = new Subscription(subscriptionId, new Customer(UUID.randomUUID(), "owner@example.com"),
                proPlan, SubscriptionState.PENDING_CANCELLATION);
        when(subscriptionRepository.findById(subscriptionId)).thenReturn(Optional.of(subscription));

        assertThatThrownBy(() -> service().undoCancel(subscriptionId, UUID.randomUUID(), null, "corr-undo-3"))
                .isInstanceOf(SubscriptionAccessDeniedException.class);

        verify(auditLogEntryRepository, never()).append(any());
    }

    @Test
    void aRepeatedUndoCancelWithTheSameIdempotencyKeyDoesNotReapplyOrWriteASecondAuditEntry() {
        UUID customerId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        Subscription subscription = new Subscription(subscriptionId, new Customer(customerId, "retry@example.com"),
                proPlan, SubscriptionState.PENDING_CANCELLATION);
        when(subscriptionRepository.findById(subscriptionId)).thenReturn(Optional.of(subscription));
        when(idempotencyService.recordIfNew(customerId, SubscriptionService.UNDO_CANCEL_OPERATION, "key-2"))
                .thenReturn(false);

        SubscriptionView view = service().undoCancel(subscriptionId, customerId, "key-2", "corr-undo-4");

        assertThat(view.state()).isEqualTo(SubscriptionState.PENDING_CANCELLATION);
        verify(auditLogEntryRepository, never()).append(any());
    }

    @Test
    void schedulingAPlanChangeFromActiveWithABillingCycleSetsPendingPlanChangeAndWritesAnAuditLogEntry() {
        UUID customerId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        UUID targetPlanId = UUID.randomUUID();
        Plan enterprisePlan = new Plan(targetPlanId, "enterprise", "Enterprise");
        Subscription subscription = Subscription.startPaidImmediately(
                subscriptionId, new Customer(customerId, "upgrader@example.com"), proPlan, FIXED_NOW);
        when(subscriptionRepository.findById(subscriptionId)).thenReturn(Optional.of(subscription));
        when(planCatalogService.findAvailablePlan(targetPlanId))
                .thenReturn(Optional.of(new PlanSummary(targetPlanId, "enterprise", "Enterprise", new BigDecimal("49.00"))));
        when(planRepository.getReferenceById(targetPlanId)).thenReturn(enterprisePlan);

        SubscriptionView view = service().schedulePlanChange(subscriptionId, customerId, targetPlanId, null, "corr-plan-1");

        // Current plan/state stay put; only pendingPlanChange reflects the scheduled edge.
        assertThat(view.state()).isEqualTo(SubscriptionState.ACTIVE);
        assertThat(view.plan().code()).isEqualTo("pro");
        assertThat(view.pendingPlanChange().code()).isEqualTo("enterprise");

        ArgumentCaptor<AuditLogEntry> auditEntry = ArgumentCaptor.forClass(AuditLogEntry.class);
        verify(auditLogEntryRepository).append(auditEntry.capture());
        assertThat(auditEntry.getValue().getOldState()).isEqualTo("ACTIVE");
        assertThat(auditEntry.getValue().getNewState()).isEqualTo("ACTIVE");
        assertThat(auditEntry.getValue().getCorrelationId()).isEqualTo("corr-plan-1");
        // A scheduled plan change is an explicit non-trigger: zero OutboxEvent rows.
        verifyNoInteractions(outboxEventRepository);
    }

    @Test
    void schedulingADowngradeToFreeFromActiveWithABillingCycleSetsPendingPlanChangeWithoutCancelingTheSubscription() {
        UUID customerId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        Subscription subscription = Subscription.startPaidImmediately(
                subscriptionId, new Customer(customerId, "downgrader@example.com"), proPlan, FIXED_NOW);
        when(subscriptionRepository.findById(subscriptionId)).thenReturn(Optional.of(subscription));
        when(planCatalogService.findAvailablePlan(planId))
                .thenReturn(Optional.of(new PlanSummary(planId, "free", "Free", new BigDecimal("0.00"))));
        when(planRepository.getReferenceById(planId)).thenReturn(freePlan);

        SubscriptionView view = service().schedulePlanChange(subscriptionId, customerId, planId, null, "corr-plan-2");

        assertThat(view.state()).isEqualTo(SubscriptionState.ACTIVE);
        assertThat(view.plan().code()).isEqualTo("pro");
        assertThat(view.pendingPlanChange().code()).isEqualTo("free");
    }

    @Test
    void schedulingAPlanChangeFromActiveWithNoBillingCycleAppliesImmediatelyAndOpensABillingCycleAnchoredToNow() {
        UUID customerId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        Subscription subscription = new Subscription(
                subscriptionId, new Customer(customerId, "free-upgrader@example.com"), freePlan, SubscriptionState.ACTIVE);
        when(subscriptionRepository.findById(subscriptionId)).thenReturn(Optional.of(subscription));
        when(planCatalogService.findAvailablePlan(planId))
                .thenReturn(Optional.of(new PlanSummary(planId, "pro", "Pro", new BigDecimal("19.00"))));
        when(planRepository.getReferenceById(planId)).thenReturn(proPlan);

        SubscriptionView view = service().schedulePlanChange(subscriptionId, customerId, planId, null, "corr-plan-3");

        assertThat(view.state()).isEqualTo(SubscriptionState.ACTIVE);
        assertThat(view.plan().code()).isEqualTo("pro");
        assertThat(view.pendingPlanChange()).isNull();
        assertThat(view.billingCycleAnchor()).isEqualTo(FIXED_NOW);
    }

    @Test
    void schedulingAPlanChangeFromActiveWithNoBillingCycleToAFreeTargetSwapsThePlanWithoutOpeningABillingCycle() {
        // Invariant 5 (a Billing Cycle exists only on a paid Plan): a free subscriber
        // "changing" to a zero-priced target — including re-selecting the free Plan
        // already active — must not open one, unlike the paid-target case above.
        UUID customerId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        UUID freeTargetId = UUID.randomUUID();
        Plan anotherFreePlan = new Plan(freeTargetId, "free", "Free");
        Subscription subscription = new Subscription(
                subscriptionId, new Customer(customerId, "free-to-free@example.com"), freePlan, SubscriptionState.ACTIVE);
        when(subscriptionRepository.findById(subscriptionId)).thenReturn(Optional.of(subscription));
        when(planCatalogService.findAvailablePlan(freeTargetId))
                .thenReturn(Optional.of(new PlanSummary(freeTargetId, "free", "Free", new BigDecimal("0.00"))));
        when(planRepository.getReferenceById(freeTargetId)).thenReturn(anotherFreePlan);

        SubscriptionView view = service().schedulePlanChange(subscriptionId, customerId, freeTargetId, null, "corr-plan-3b");

        assertThat(view.state()).isEqualTo(SubscriptionState.ACTIVE);
        assertThat(view.plan().code()).isEqualTo("free");
        assertThat(view.pendingPlanChange()).isNull();
        assertThat(view.billingCycleAnchor()).isNull();
    }

    @Test
    void schedulingAPlanChangeTargetingARetiredPlanIsRejected() {
        UUID customerId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        UUID retiredPlanId = UUID.randomUUID();
        Subscription subscription = Subscription.startPaidImmediately(
                subscriptionId, new Customer(customerId, "retired-target@example.com"), proPlan, FIXED_NOW);
        when(subscriptionRepository.findById(subscriptionId)).thenReturn(Optional.of(subscription));
        when(planCatalogService.findAvailablePlan(retiredPlanId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().schedulePlanChange(subscriptionId, customerId, retiredPlanId, null, "corr-plan-4"))
                .isInstanceOf(PlanUnavailableForSignupException.class);

        verify(auditLogEntryRepository, never()).append(any());
    }

    @Test
    void schedulingAPlanChangeOnATrialingSubscriptionIsRejected() {
        UUID customerId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        Subscription subscription = Subscription.startTrial(
                subscriptionId, new Customer(customerId, "trialist@example.com"), proPlan, FIXED_NOW);
        when(subscriptionRepository.findById(subscriptionId)).thenReturn(Optional.of(subscription));
        when(planCatalogService.findAvailablePlan(planId))
                .thenReturn(Optional.of(new PlanSummary(planId, "enterprise", "Enterprise", new BigDecimal("49.00"))));

        assertThatThrownBy(() -> service().schedulePlanChange(subscriptionId, customerId, planId, null, "corr-plan-5"))
                .isInstanceOf(SubscriptionNotEligibleForPlanChangeException.class);

        verify(auditLogEntryRepository, never()).append(any());
    }

    @Test
    void schedulingAPlanChangeForAWrongOwnerIsRejectedWithAccessDenied() {
        UUID subscriptionId = UUID.randomUUID();
        Subscription subscription = Subscription.startPaidImmediately(
                subscriptionId, new Customer(UUID.randomUUID(), "owner@example.com"), proPlan, FIXED_NOW);
        when(subscriptionRepository.findById(subscriptionId)).thenReturn(Optional.of(subscription));

        assertThatThrownBy(() -> service().schedulePlanChange(subscriptionId, UUID.randomUUID(), planId, null, "corr-plan-6"))
                .isInstanceOf(SubscriptionAccessDeniedException.class);

        verify(auditLogEntryRepository, never()).append(any());
    }

    @Test
    void aRepeatedPlanChangeWithTheSameIdempotencyKeyDoesNotReapplyOrWriteASecondAuditEntry() {
        UUID customerId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        Subscription subscription = Subscription.startPaidImmediately(
                subscriptionId, new Customer(customerId, "retry@example.com"), proPlan, FIXED_NOW);
        when(subscriptionRepository.findById(subscriptionId)).thenReturn(Optional.of(subscription));
        when(idempotencyService.recordIfNew(customerId, SubscriptionService.PLAN_CHANGE_OPERATION, "key-5"))
                .thenReturn(false);

        SubscriptionView view = service().schedulePlanChange(subscriptionId, customerId, planId, "key-5", "corr-plan-7");

        // Untouched: no pendingPlanChange — the transition was never re-run for this
        // deduplicated retry.
        assertThat(view.pendingPlanChange()).isNull();
        verify(auditLogEntryRepository, never()).append(any());
        verify(planCatalogService, never()).findAvailablePlan(any());
    }

    @Test
    void suspendFromTrialingTransitionsImmediatelyToSuspendedAndWritesASystemAuditLogEntry() {
        UUID subscriptionId = UUID.randomUUID();
        Subscription subscription = Subscription.startTrial(
                subscriptionId, new Customer(UUID.randomUUID(), "trialist@example.com"), proPlan, FIXED_NOW);
        when(subscriptionRepository.findById(subscriptionId)).thenReturn(Optional.of(subscription));

        service().suspend(subscriptionId, LocalDate.of(2026, 8, 22), "corr-suspend-1");

        assertThat(subscription.getState()).isEqualTo(SubscriptionState.SUSPENDED);
        assertThat(subscription.getDunningBillingPeriod()).isEqualTo(LocalDate.of(2026, 8, 22));
        verify(subscriptionRepository).saveAndFlush(subscription);

        ArgumentCaptor<AuditLogEntry> auditEntry = ArgumentCaptor.forClass(AuditLogEntry.class);
        verify(auditLogEntryRepository).append(auditEntry.capture());
        assertThat(auditEntry.getValue().getActorType()).isEqualTo(ActorType.SYSTEM);
        assertThat(auditEntry.getValue().getOldState()).isEqualTo("TRIALING");
        assertThat(auditEntry.getValue().getNewState()).isEqualTo("SUSPENDED");
        assertThat(auditEntry.getValue().getCorrelationId()).isEqualTo("corr-suspend-1");

        ArgumentCaptor<OutboxEvent> outboxEvent = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(outboxEvent.capture());
        assertThat(outboxEvent.getValue().getEventType()).isEqualTo("PAYMENT_FAILED");
        assertThat(outboxEvent.getValue().getPayload())
                .contains(subscriptionId.toString())
                .contains("2026-08-22")
                .contains("corr-suspend-1");
    }

    @Test
    void suspendFromActiveTransitionsImmediatelyToSuspendedAndWritesASystemAuditLogEntry() {
        UUID subscriptionId = UUID.randomUUID();
        Subscription subscription = Subscription.startPaidImmediately(
                subscriptionId, new Customer(UUID.randomUUID(), "active@example.com"), proPlan, FIXED_NOW);
        when(subscriptionRepository.findById(subscriptionId)).thenReturn(Optional.of(subscription));

        service().suspend(subscriptionId, LocalDate.of(2026, 8, 22), "corr-suspend-2");

        assertThat(subscription.getState()).isEqualTo(SubscriptionState.SUSPENDED);

        ArgumentCaptor<AuditLogEntry> auditEntry = ArgumentCaptor.forClass(AuditLogEntry.class);
        verify(auditLogEntryRepository).append(auditEntry.capture());
        assertThat(auditEntry.getValue().getOldState()).isEqualTo("ACTIVE");
        assertThat(auditEntry.getValue().getNewState()).isEqualTo("SUSPENDED");
        verify(outboxEventRepository).save(any(OutboxEvent.class));
    }

    @Test
    void suspendOnAnIneligibleStateIsRejectedAndWritesNoAuditLogEntry() {
        UUID subscriptionId = UUID.randomUUID();
        Subscription subscription = new Subscription(
                subscriptionId, new Customer(UUID.randomUUID(), "canceled@example.com"), proPlan, SubscriptionState.CANCELED);
        when(subscriptionRepository.findById(subscriptionId)).thenReturn(Optional.of(subscription));

        assertThatThrownBy(() -> service().suspend(subscriptionId, LocalDate.of(2026, 8, 22), "corr-suspend-3"))
                .isInstanceOf(SubscriptionNotEligibleForSuspensionException.class);

        verify(subscriptionRepository, never()).saveAndFlush(any());
        verify(auditLogEntryRepository, never()).append(any());
        // Only the first failure for a Billing Cycle reaches this method at all (a
        // day 3/7 retry failure reschedules or cancels instead) -- an ineligible state
        // here is a genuine rejection, not a retry, so it must still write no event.
        verifyNoInteractions(outboxEventRepository);
    }

    @Test
    void suspendOfANonexistentSubscriptionFailsFastInsteadOfSilentlyNoOping() {
        UUID subscriptionId = UUID.randomUUID();
        when(subscriptionRepository.findById(subscriptionId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().suspend(subscriptionId, LocalDate.of(2026, 8, 22), "corr-suspend-4"))
                .isInstanceOf(IllegalStateException.class);

        verify(auditLogEntryRepository, never()).append(any());
        verifyNoInteractions(outboxEventRepository);
    }

    @Test
    void cancelForDunningExhaustionFromSuspendedTransitionsImmediatelyToCanceledAndWritesASystemAuditLogEntry() {
        UUID subscriptionId = UUID.randomUUID();
        Subscription subscription = Subscription.startPaidImmediately(
                subscriptionId, new Customer(UUID.randomUUID(), "exhausted@example.com"), proPlan, FIXED_NOW);
        subscription.suspend(LocalDate.of(2026, 8, 1));
        when(subscriptionRepository.findById(subscriptionId)).thenReturn(Optional.of(subscription));

        service().cancelForDunningExhaustion(subscriptionId, "corr-exhaust-1");

        assertThat(subscription.getState()).isEqualTo(SubscriptionState.CANCELED);
        verify(subscriptionRepository).saveAndFlush(subscription);

        ArgumentCaptor<AuditLogEntry> auditEntry = ArgumentCaptor.forClass(AuditLogEntry.class);
        verify(auditLogEntryRepository).append(auditEntry.capture());
        assertThat(auditEntry.getValue().getActorType()).isEqualTo(ActorType.SYSTEM);
        assertThat(auditEntry.getValue().getOldState()).isEqualTo("SUSPENDED");
        assertThat(auditEntry.getValue().getNewState()).isEqualTo("CANCELED");
        assertThat(auditEntry.getValue().getCorrelationId()).isEqualTo("corr-exhaust-1");
        // These customers already got the one failure alert at initial suspension --
        // a second, cancellation-confirmed email would be an undocumented 5th trigger.
        verifyNoInteractions(outboxEventRepository);
    }

    @Test
    void cancelForDunningExhaustionOnAnAlreadyCanceledSubscriptionIsRejectedAndWritesNoAuditLogEntry() {
        UUID subscriptionId = UUID.randomUUID();
        Subscription subscription = new Subscription(
                subscriptionId, new Customer(UUID.randomUUID(), "already-canceled@example.com"), proPlan, SubscriptionState.CANCELED);
        when(subscriptionRepository.findById(subscriptionId)).thenReturn(Optional.of(subscription));

        assertThatThrownBy(() -> service().cancelForDunningExhaustion(subscriptionId, "corr-exhaust-2"))
                .isInstanceOf(SubscriptionAlreadyCanceledException.class);

        verify(subscriptionRepository, never()).saveAndFlush(any());
        verify(auditLogEntryRepository, never()).append(any());
        verifyNoInteractions(outboxEventRepository);
    }

    @Test
    void cancelForDunningExhaustionOfANonexistentSubscriptionFailsFastInsteadOfSilentlyNoOping() {
        UUID subscriptionId = UUID.randomUUID();
        when(subscriptionRepository.findById(subscriptionId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().cancelForDunningExhaustion(subscriptionId, "corr-exhaust-3"))
                .isInstanceOf(IllegalStateException.class);

        verify(auditLogEntryRepository, never()).append(any());
        verifyNoInteractions(outboxEventRepository);
    }

    @Test
    void cancelForDisputeFromActiveTransitionsImmediatelyToCanceledAndWritesASystemAuditLogEntry() {
        UUID subscriptionId = UUID.randomUUID();
        Subscription subscription = Subscription.startPaidImmediately(
                subscriptionId, new Customer(UUID.randomUUID(), "disputed@example.com"), proPlan, FIXED_NOW);
        when(subscriptionRepository.findById(subscriptionId)).thenReturn(Optional.of(subscription));

        service().cancelForDispute(subscriptionId, "corr-dispute-1");

        assertThat(subscription.getState()).isEqualTo(SubscriptionState.CANCELED);
        verify(subscriptionRepository).saveAndFlush(subscription);
        verify(invoiceCancellationPort, never()).failStillOpenInvoice(any(), any());

        ArgumentCaptor<AuditLogEntry> auditEntry = ArgumentCaptor.forClass(AuditLogEntry.class);
        verify(auditLogEntryRepository).append(auditEntry.capture());
        assertThat(auditEntry.getValue().getActorType()).isEqualTo(ActorType.SYSTEM);
        assertThat(auditEntry.getValue().getOldState()).isEqualTo("ACTIVE");
        assertThat(auditEntry.getValue().getNewState()).isEqualTo("CANCELED");
        assertThat(auditEntry.getValue().getCorrelationId()).isEqualTo("corr-dispute-1");
        // A dispute cancellation is explicitly silent -- zero OutboxEvent rows.
        verifyNoInteractions(outboxEventRepository);
    }

    @Test
    void cancelForDisputeFromSuspendedTransitionsImmediatelyToCanceledAndFailsTheStillOpenInvoice() {
        UUID subscriptionId = UUID.randomUUID();
        Subscription subscription = Subscription.startPaidImmediately(
                subscriptionId, new Customer(UUID.randomUUID(), "disputed-suspended@example.com"), proPlan, FIXED_NOW);
        subscription.suspend(LocalDate.of(2026, 8, 1));
        when(subscriptionRepository.findById(subscriptionId)).thenReturn(Optional.of(subscription));

        service().cancelForDispute(subscriptionId, "corr-dispute-2");

        assertThat(subscription.getState()).isEqualTo(SubscriptionState.CANCELED);
        verify(invoiceCancellationPort).failStillOpenInvoice(subscriptionId, LocalDate.of(2026, 8, 1));

        ArgumentCaptor<AuditLogEntry> auditEntry = ArgumentCaptor.forClass(AuditLogEntry.class);
        verify(auditLogEntryRepository).append(auditEntry.capture());
        assertThat(auditEntry.getValue().getOldState()).isEqualTo("SUSPENDED");
        assertThat(auditEntry.getValue().getNewState()).isEqualTo("CANCELED");
        verifyNoInteractions(outboxEventRepository);
    }

    @Test
    void cancelForDisputeOnAnAlreadyCanceledSubscriptionIsASafeNoOpWritingNoAuditLogEntry() {
        UUID subscriptionId = UUID.randomUUID();
        Subscription subscription = new Subscription(subscriptionId,
                new Customer(UUID.randomUUID(), "already-canceled-dispute@example.com"), proPlan, SubscriptionState.CANCELED);
        when(subscriptionRepository.findById(subscriptionId)).thenReturn(Optional.of(subscription));

        service().cancelForDispute(subscriptionId, "corr-dispute-3");

        verify(subscriptionRepository, never()).saveAndFlush(any());
        verify(auditLogEntryRepository, never()).append(any());
        verifyNoInteractions(invoiceCancellationPort);
        verifyNoInteractions(outboxEventRepository);
    }

    @Test
    void cancelForDisputeFromTrialingIsRejectedAndWritesNoAuditLogEntry() {
        UUID subscriptionId = UUID.randomUUID();
        Subscription subscription = Subscription.startTrial(
                subscriptionId, new Customer(UUID.randomUUID(), "trialist-dispute@example.com"), proPlan, FIXED_NOW);
        when(subscriptionRepository.findById(subscriptionId)).thenReturn(Optional.of(subscription));

        assertThatThrownBy(() -> service().cancelForDispute(subscriptionId, "corr-dispute-4"))
                .isInstanceOf(SubscriptionNotEligibleForDisputeCancellationException.class);

        verify(subscriptionRepository, never()).saveAndFlush(any());
        verify(auditLogEntryRepository, never()).append(any());
    }

    @Test
    void cancelForDisputeOfANonexistentSubscriptionFailsFastInsteadOfSilentlyNoOping() {
        UUID subscriptionId = UUID.randomUUID();
        when(subscriptionRepository.findById(subscriptionId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().cancelForDispute(subscriptionId, "corr-dispute-5"))
                .isInstanceOf(IllegalStateException.class);

        verify(auditLogEntryRepository, never()).append(any());
    }

    @Test
    void scheduleRetryMovesTheDueDateAndWritesNoAuditLogEntrySinceStateDoesNotChange() {
        UUID subscriptionId = UUID.randomUUID();
        Subscription subscription = new Subscription(subscriptionId,
                new Customer(UUID.randomUUID(), "suspended-retry@example.com"), proPlan, SubscriptionState.SUSPENDED,
                FIXED_NOW, LocalDate.of(2026, 8, 22));
        when(subscriptionRepository.findById(subscriptionId)).thenReturn(Optional.of(subscription));

        service().scheduleRetry(subscriptionId, LocalDate.of(2026, 8, 23));

        assertThat(subscription.getDueDate()).isEqualTo(LocalDate.of(2026, 8, 23));
        verify(subscriptionRepository).saveAndFlush(subscription);
        verify(auditLogEntryRepository, never()).append(any());
    }

    @Test
    void scheduleRetryOfANonexistentSubscriptionFailsFastInsteadOfSilentlyNoOping() {
        UUID subscriptionId = UUID.randomUUID();
        when(subscriptionRepository.findById(subscriptionId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().scheduleRetry(subscriptionId, LocalDate.of(2026, 8, 23)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void notifyTrialEndingSoonMarksTheSubscriptionNotifiedAndWritesATrialEndingSoonOutboxEventWithNoAuditLogEntry() {
        UUID subscriptionId = UUID.randomUUID();
        Instant trialEndsAt = FIXED_NOW.plus(Duration.ofDays(2));
        Subscription subscription = Subscription.startTrial(
                subscriptionId, new Customer(UUID.randomUUID(), "trialist@example.com"), proPlan, trialEndsAt);
        when(subscriptionRepository.findById(subscriptionId)).thenReturn(Optional.of(subscription));

        service().notifyTrialEndingSoon(subscriptionId, FIXED_NOW);

        assertThat(subscription.getTrialEndingSoonNotifiedAt()).isEqualTo(FIXED_NOW);
        verify(subscriptionRepository).saveAndFlush(subscription);
        verify(auditLogEntryRepository, never()).append(any());

        ArgumentCaptor<OutboxEvent> outboxEvent = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(outboxEvent.capture());
        assertThat(outboxEvent.getValue().getEventType()).isEqualTo("TRIAL_ENDING_SOON");
        assertThat(outboxEvent.getValue().getPayload())
                .contains(subscriptionId.toString())
                .contains(trialEndsAt.toString());
    }

    @Test
    void notifyTrialEndingSoonOfANonexistentSubscriptionFailsFastInsteadOfSilentlyNoOping() {
        UUID subscriptionId = UUID.randomUUID();
        when(subscriptionRepository.findById(subscriptionId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().notifyTrialEndingSoon(subscriptionId, FIXED_NOW))
                .isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(outboxEventRepository);
    }

    @Test
    void retryPaymentFromSuspendedLoadsChargeDetailsAndDelegatesToDunningRetryCharge() {
        UUID customerId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        Subscription subscription = new Subscription(
                subscriptionId, new Customer(customerId, "suspended@example.com"), proPlan, SubscriptionState.SUSPENDED);
        ChargeableSubscription chargeable = new ChargeableSubscription(
                subscriptionId, "tok_visa", new BigDecimal("19.00"), UUID.randomUUID(), LocalDate.of(2026, 8, 1), 1, true);
        when(subscriptionRepository.findById(subscriptionId)).thenReturn(Optional.of(subscription));
        when(chargeableSubscriptionPort.loadForCharge(subscriptionId)).thenReturn(chargeable);
        when(dunningRetryCharge.attempt(subscriptionId, chargeable, FIXED_NOW))
                .thenReturn(new DunningRetryChargeResult.Recovered("gw-txn-1"));

        service().retryPayment(subscriptionId, customerId, null);

        verify(dunningRetryCharge).attempt(subscriptionId, chargeable, FIXED_NOW);
    }

    @Test
    void retryPaymentOnANonSuspendedSubscriptionIsRejectedWithoutChargingTheGateway() {
        UUID customerId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        Subscription subscription = Subscription.startPaidImmediately(
                subscriptionId, new Customer(customerId, "active@example.com"), proPlan, FIXED_NOW);
        when(subscriptionRepository.findById(subscriptionId)).thenReturn(Optional.of(subscription));

        assertThatThrownBy(() -> service().retryPayment(subscriptionId, customerId, null))
                .isInstanceOf(SubscriptionNotSuspendedException.class);

        verifyNoInteractions(chargeableSubscriptionPort, dunningRetryCharge);
    }

    @Test
    void retryPaymentForAWrongOwnerIsRejectedWithAccessDenied() {
        UUID subscriptionId = UUID.randomUUID();
        Subscription subscription = new Subscription(subscriptionId,
                new Customer(UUID.randomUUID(), "owner@example.com"), proPlan, SubscriptionState.SUSPENDED);
        when(subscriptionRepository.findById(subscriptionId)).thenReturn(Optional.of(subscription));

        assertThatThrownBy(() -> service().retryPayment(subscriptionId, UUID.randomUUID(), null))
                .isInstanceOf(SubscriptionAccessDeniedException.class);

        verifyNoInteractions(chargeableSubscriptionPort, dunningRetryCharge);
    }

    @Test
    void aRepeatedRetryPaymentWithTheSameIdempotencyKeyDoesNotChargeTheGatewayAgain() {
        UUID customerId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        Subscription subscription = new Subscription(
                subscriptionId, new Customer(customerId, "retry@example.com"), proPlan, SubscriptionState.SUSPENDED);
        when(subscriptionRepository.findById(subscriptionId)).thenReturn(Optional.of(subscription));
        when(idempotencyService.recordIfNew(customerId, SubscriptionService.RETRY_PAYMENT_OPERATION, "key-1"))
                .thenReturn(false);

        service().retryPayment(subscriptionId, customerId, "key-1");

        verifyNoInteractions(chargeableSubscriptionPort, dunningRetryCharge);
    }

    @Test
    void retryPaymentOnANonSuspendedSubscriptionWithAnIdempotencyKeyReleasesIt() {
        UUID customerId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        Subscription subscription = Subscription.startPaidImmediately(
                subscriptionId, new Customer(customerId, "active@example.com"), proPlan, FIXED_NOW);
        when(subscriptionRepository.findById(subscriptionId)).thenReturn(Optional.of(subscription));
        when(idempotencyService.recordIfNew(customerId, SubscriptionService.RETRY_PAYMENT_OPERATION, "key-2"))
                .thenReturn(true);

        assertThatThrownBy(() -> service().retryPayment(subscriptionId, customerId, "key-2"))
                .isInstanceOf(SubscriptionNotSuspendedException.class);

        verify(idempotencyService).release(customerId, SubscriptionService.RETRY_PAYMENT_OPERATION, "key-2");
    }

    @Test
    void retryPaymentThatFailsTransientlyReleasesTheIdempotencyKeyAndThrows() {
        UUID customerId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        Subscription subscription = new Subscription(
                subscriptionId, new Customer(customerId, "suspended@example.com"), proPlan, SubscriptionState.SUSPENDED);
        ChargeableSubscription chargeable = new ChargeableSubscription(
                subscriptionId, "tok_visa", new BigDecimal("19.00"), UUID.randomUUID(), LocalDate.of(2026, 8, 1), 1, true);
        when(subscriptionRepository.findById(subscriptionId)).thenReturn(Optional.of(subscription));
        when(idempotencyService.recordIfNew(customerId, SubscriptionService.RETRY_PAYMENT_OPERATION, "key-3"))
                .thenReturn(true);
        when(chargeableSubscriptionPort.loadForCharge(subscriptionId)).thenReturn(chargeable);
        when(dunningRetryCharge.attempt(subscriptionId, chargeable, FIXED_NOW))
                .thenReturn(new DunningRetryChargeResult.FailedTransiently("gateway_timeout"));

        assertThatThrownBy(() -> service().retryPayment(subscriptionId, customerId, "key-3"))
                .isInstanceOf(PaymentGatewayUnavailableException.class);

        verify(idempotencyService).release(customerId, SubscriptionService.RETRY_PAYMENT_OPERATION, "key-3");
    }

    @Test
    void retryPaymentThatIsDeclinedDoesNotThrowAndKeepsTheIdempotencyKey() {
        UUID customerId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        Subscription subscription = new Subscription(
                subscriptionId, new Customer(customerId, "suspended@example.com"), proPlan, SubscriptionState.SUSPENDED);
        ChargeableSubscription chargeable = new ChargeableSubscription(
                subscriptionId, "tok_visa", new BigDecimal("19.00"), UUID.randomUUID(), LocalDate.of(2026, 8, 1), 1, true);
        when(subscriptionRepository.findById(subscriptionId)).thenReturn(Optional.of(subscription));
        when(idempotencyService.recordIfNew(customerId, SubscriptionService.RETRY_PAYMENT_OPERATION, "key-4"))
                .thenReturn(true);
        when(chargeableSubscriptionPort.loadForCharge(subscriptionId)).thenReturn(chargeable);
        when(dunningRetryCharge.attempt(subscriptionId, chargeable, FIXED_NOW))
                .thenReturn(new DunningRetryChargeResult.Declined(DunningRetryOutcome.RESCHEDULED, "card_declined"));

        service().retryPayment(subscriptionId, customerId, "key-4");

        // A decline is a normal recorded outcome, not a rejection of the request itself
        // -- the key stays consumed so a client retry of the exact same request doesn't
        // trigger a second Payment Attempt.
        verify(idempotencyService, never()).release(any(), any(), any());
    }

    @Test
    void retryPaymentWithNoIdempotencyKeyNeverTouchesIdempotencyServiceEvenOnRejection() {
        UUID customerId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        Subscription subscription = Subscription.startPaidImmediately(
                subscriptionId, new Customer(customerId, "active@example.com"), proPlan, FIXED_NOW);
        when(subscriptionRepository.findById(subscriptionId)).thenReturn(Optional.of(subscription));

        assertThatThrownBy(() -> service().retryPayment(subscriptionId, customerId, null))
                .isInstanceOf(SubscriptionNotSuspendedException.class);

        verifyNoInteractions(idempotencyService);
    }
}
