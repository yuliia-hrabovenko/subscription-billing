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
import com.subscriptionbilling.billingjob.ChargeTrigger;
import com.subscriptionbilling.billingjob.charge.ChargeableSubscription;
import com.subscriptionbilling.billingjob.charge.ChargeableSubscriptionPort;
import com.subscriptionbilling.billingjob.dunning.DunningRetryCharge;
import com.subscriptionbilling.billingjob.dunning.DunningRetryChargeResult;
import com.subscriptionbilling.billingjob.invoicing.InvoiceCancellationPort;
import com.subscriptionbilling.notifications.outbox.OutboxEvent;
import com.subscriptionbilling.notifications.outbox.OutboxEventRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Owns the Subscription lifecycle's entry points: bringing a Subscription into
 * existence, fetching one back for its owner, and canceling/undoing a cancellation.
 * Every write here is one atomic use case — persistence, its audit trail, and (for
 * signup) token issuance all commit or fail together — not a sequence of
 * independently-failable steps.
 */
@Service
public class SubscriptionService {

    /**
     * A Trial runs for 14 days from signup before it auto-converts to a paid charge
     * (or is canceled first).
     */
    static final Duration TRIAL_DURATION = Duration.ofDays(14);

    /** {@link IdempotencyService} operation name for {@link #cancel}. */
    static final String CANCEL_OPERATION = "cancel";

    /** {@link IdempotencyService} operation name for {@link #undoCancel}. */
    static final String UNDO_CANCEL_OPERATION = "undo-cancel";

    /** {@link IdempotencyService} operation name for {@link #schedulePlanChange}. */
    static final String PLAN_CHANGE_OPERATION = "plan-change";

    /** {@link IdempotencyService} operation name for {@link #retryPayment}. */
    static final String RETRY_PAYMENT_OPERATION = "retry-payment";

    private final CustomerRepository customerRepository;
    private final PlanRepository planRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final PlanCatalogService planCatalogService;
    private final AuditLogEntryRepository auditLogEntryRepository;
    private final CustomerTokenIssuer tokenIssuer;
    private final IdempotencyService idempotencyService;
    private final ChargeableSubscriptionPort chargeableSubscriptionPort;
    private final DunningRetryCharge dunningRetryCharge;
    private final InvoiceCancellationPort invoiceCancellationPort;
    private final OutboxEventRepository outboxEventRepository;
    private final Clock clock;

    @Autowired
    public SubscriptionService(CustomerRepository customerRepository, PlanRepository planRepository,
                                SubscriptionRepository subscriptionRepository, PlanCatalogService planCatalogService,
                                AuditLogEntryRepository auditLogEntryRepository, CustomerTokenIssuer tokenIssuer,
                                IdempotencyService idempotencyService, ChargeableSubscriptionPort chargeableSubscriptionPort,
                                DunningRetryCharge dunningRetryCharge, InvoiceCancellationPort invoiceCancellationPort,
                                OutboxEventRepository outboxEventRepository) {
        this(customerRepository, planRepository, subscriptionRepository, planCatalogService, auditLogEntryRepository,
                tokenIssuer, idempotencyService, chargeableSubscriptionPort, dunningRetryCharge, invoiceCancellationPort,
                outboxEventRepository, Clock.systemUTC());
    }

    SubscriptionService(CustomerRepository customerRepository, PlanRepository planRepository,
                         SubscriptionRepository subscriptionRepository, PlanCatalogService planCatalogService,
                         AuditLogEntryRepository auditLogEntryRepository, CustomerTokenIssuer tokenIssuer,
                         IdempotencyService idempotencyService, ChargeableSubscriptionPort chargeableSubscriptionPort,
                         DunningRetryCharge dunningRetryCharge, InvoiceCancellationPort invoiceCancellationPort,
                         OutboxEventRepository outboxEventRepository, Clock clock) {
        this.customerRepository = customerRepository;
        this.planRepository = planRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.planCatalogService = planCatalogService;
        this.auditLogEntryRepository = auditLogEntryRepository;
        this.tokenIssuer = tokenIssuer;
        this.idempotencyService = idempotencyService;
        this.chargeableSubscriptionPort = chargeableSubscriptionPort;
        this.dunningRetryCharge = dunningRetryCharge;
        this.invoiceCancellationPort = invoiceCancellationPort;
        this.outboxEventRepository = outboxEventRepository;
        this.clock = clock;
    }

    /**
     * Takes exactly one of three paths based on the target Plan's price and {@code
     * command.useTrial()}: free ({@code [*] -> active}, no Billing Cycle), Trial
     * ({@code [*] -> trialing}, no Billing Cycle until it converts), or immediate-paid
     * ({@code [*] -> active} with a Billing Cycle anchored to now — no charge is
     * actually performed by this method; money movement is a separate concern).
     *
     * <p>Re-subscribing after a cancellation always produces a brand-new Subscription
     * row with fresh Trial eligibility, never a reactivated old one.
     *
     * @param command the signup request, including which Plan and whether a Trial was
     *                requested
     * @return the new Subscription's identity/state/Plan, a bearer token for its
     *         Customer, and whichever of {@code trialEndsAt}/{@code billingCycleAnchor}
     *         applies to the path taken (both null for a free-Plan signup)
     * @throws PlanUnavailableForSignupException if the target Plan doesn't exist, is
     *         retired, or has no current price
     * @throws DuplicateSubscriptionException    if the identified Customer already has
     *         a non-{@code canceled} Subscription
     * @throws PaymentMethodRequiredException    if the target Plan is paid and no
     *         payment method token was supplied
     */
    @Transactional
    public SubscriptionSignupResult signUp(SignupCommand command) {
        PlanSummary plan = planCatalogService.findAvailablePlan(command.planId())
                .orElseThrow(() -> new PlanUnavailableForSignupException(command.planId()));
        boolean isFreePlan = plan.currentPrice().signum() == 0;

        Customer customer = resolveCustomer(command);
        if (command.existingCustomerId() != null
                && subscriptionRepository.existsByCustomerIdAndStateNot(customer.getId(), SubscriptionState.CANCELED)) {
            throw new DuplicateSubscriptionException(customer.getId());
        }

        Subscription subscription;
        if (isFreePlan) {
            subscription = new Subscription(
                    UUID.randomUUID(), customer, planRepository.getReferenceById(command.planId()),
                    SubscriptionState.ACTIVE);
        } else {
            if (!StringUtils.hasText(command.paymentMethodToken())) {
                throw new PaymentMethodRequiredException(command.planId());
            }
            customer.setPaymentMethodToken(command.paymentMethodToken());

            Instant now = Instant.now(clock);
            subscription = command.useTrial()
                    ? Subscription.startTrial(
                            UUID.randomUUID(), customer, planRepository.getReferenceById(command.planId()), now.plus(TRIAL_DURATION))
                    : Subscription.startPaidImmediately(
                            UUID.randomUUID(), customer, planRepository.getReferenceById(command.planId()), now);
        }
        subscriptionRepository.save(subscription);

        auditLogEntryRepository.append(new AuditLogEntry(
                UUID.randomUUID(), subscription.getId(), ActorType.CUSTOMER, null, subscription.getState().name(),
                command.correlationId()));

        String accessToken = tokenIssuer.issueFor(customer.getId());
        return new SubscriptionSignupResult(subscription.getId(), subscription.getState(), plan.id(), accessToken,
                subscription.getTrialEndsAt(), subscription.getBillingCycleAnchor());
    }

    /**
     * Fetches a Subscription for the Customer identified by their own bearer token.
     * Not-found and not-owned collapse into the same {@link SubscriptionAccessDeniedException}
     * — see its Javadoc for why that's the ownership check's whole point, not an
     * oversight.
     */
    @Transactional(readOnly = true)
    public SubscriptionView getOwnSubscription(UUID subscriptionId, UUID authenticatedCustomerId) {
        return SubscriptionView.from(ownedSubscription(subscriptionId, authenticatedCustomerId));
    }

    /**
     * Fetches any Subscription by id for an Admin caller — no ownership
     * check, unlike {@link #getOwnSubscription}.
     *
     * @throws SubscriptionNotFoundException if {@code subscriptionId} doesn't exist
     */
    @Transactional(readOnly = true)
    public AdminSubscriptionView getForAdmin(UUID subscriptionId) {
        Subscription subscription = subscriptionRepository.findById(subscriptionId)
                .orElseThrow(() -> new SubscriptionNotFoundException(subscriptionId));
        return AdminSubscriptionView.from(subscription);
    }

    /**
     * Every Subscription a given Customer has ever had, for an Admin's customer-detail
     * view — no ownership check, and no pagination (see {@link
     * SubscriptionRepository#findByCustomerId}'s Javadoc for why).
     */
    @Transactional(readOnly = true)
    public List<AdminSubscriptionView> listForCustomerAsAdmin(UUID customerId) {
        return subscriptionRepository.findByCustomerId(customerId).stream().map(AdminSubscriptionView::from).toList();
    }

    /**
     * See {@link Subscription#cancel()} for which originating state produces immediate
     * vs. deferred termination. A blank or absent {@code idempotencyKey} skips
     * deduplication — see {@link #applyTransition} for the dedup/release contract.
     *
     * <p>Canceling directly from {@code suspended} also fails that Billing Cycle's
     * still-open Invoice, via {@link InvoiceCancellationPort#failStillOpenInvoice}: the
     * cancellation ends Dunning's retry sequence early, so the Invoice becomes terminal
     * {@code failed} immediately even though fewer than the full retry bound was used.
     * The Billing Cycle is read from {@link Subscription#getDunningBillingPeriod()}
     * before the transition clears it, so this only fires for the {@code suspended}
     * origin — every other origin has no Invoice still open.
     *
     * <p>A request that actually applies the transition (immediate or deferred alike)
     * also writes a {@code CANCELLATION_CONFIRMED} {@link OutboxEvent} in the same
     * transaction, confirming the request was recorded rather than that the Subscription
     * has reached {@code canceled}. A deduplicated retry writes no second event.
     *
     * @param subscriptionId          the Subscription to cancel
     * @param authenticatedCustomerId the Customer the caller's bearer token identifies
     * @param idempotencyKey          the client-supplied {@code Idempotency-Key} header
     *                                value, or null/blank if none was sent
     * @param correlationId           rides along on the written {@link
     *                                com.subscriptionbilling.audit.AuditLogEntry}
     * @return the Subscription's state after this request — either the just-applied
     *         transition, or (for a deduplicated retry) its unchanged current state
     * @throws SubscriptionAccessDeniedException              if the Subscription doesn't
     *         exist or doesn't belong to this Customer
     * @throws SubscriptionAlreadyPendingCancellationException if a cancellation is
     *         already pending
     * @throws SubscriptionAlreadyCanceledException            if already {@code canceled}
     */
    @Transactional
    public SubscriptionView cancel(UUID subscriptionId, UUID authenticatedCustomerId, String idempotencyKey,
                                    String correlationId) {
        AtomicReference<LocalDate> stillOpenBillingPeriod = new AtomicReference<>();
        AtomicBoolean transitionApplied = new AtomicBoolean(false);
        SubscriptionView view = applyTransition(subscriptionId, authenticatedCustomerId, idempotencyKey, CANCEL_OPERATION,
                subscription -> {
                    stillOpenBillingPeriod.set(subscription.getDunningBillingPeriod());
                    subscription.cancel();
                    transitionApplied.set(true);
                }, correlationId);
        LocalDate billingPeriod = stillOpenBillingPeriod.get();
        if (billingPeriod != null) {
            invoiceCancellationPort.failStillOpenInvoice(subscriptionId, billingPeriod);
        }
        if (transitionApplied.get()) {
            outboxEventRepository.save(new OutboxEvent(UUID.randomUUID(), "CANCELLATION_CONFIRMED",
                    cancellationConfirmedPayload(subscriptionId, authenticatedCustomerId)));
        }
        return view;
    }

    /**
     * See {@link Subscription#undoCancel()} for the single originating state this
     * requires. Idempotency handling mirrors {@link #cancel} — see {@link
     * #applyTransition}.
     *
     * @param subscriptionId          the Subscription to restore to {@code active}
     * @param authenticatedCustomerId the Customer the caller's bearer token identifies
     * @param idempotencyKey          the client-supplied {@code Idempotency-Key} header
     *                                value, or null/blank if none was sent
     * @param correlationId           rides along on the written {@link
     *                                com.subscriptionbilling.audit.AuditLogEntry}
     * @return the Subscription's state after this request — either the just-applied
     *         transition, or (for a deduplicated retry) its unchanged current state
     * @throws SubscriptionAccessDeniedException           if the Subscription doesn't
     *         exist or doesn't belong to this Customer
     * @throws SubscriptionNotPendingCancellationException if not currently {@code
     *         pending_cancellation}
     */
    @Transactional
    public SubscriptionView undoCancel(UUID subscriptionId, UUID authenticatedCustomerId, String idempotencyKey,
                                        String correlationId) {
        return applyTransition(subscriptionId, authenticatedCustomerId, idempotencyKey, UNDO_CANCEL_OPERATION,
                Subscription::undoCancel, correlationId);
    }

    /**
     * See {@link Subscription#schedulePlanChange} for the free-to-paid-immediate vs.
     * paid-to-paid/paid-to-free-deferred split. Idempotency handling mirrors {@link
     * #cancel} — see {@link #applyTransition}. The target Plan's availability is checked
     * inside the same guarded section as the transition itself, so a rejection here also
     * releases a just-recorded idempotency key rather than burning it.
     *
     * @param subscriptionId          the Subscription to change the Plan of
     * @param authenticatedCustomerId the Customer the caller's bearer token identifies
     * @param targetPlanId            the Plan being switched to
     * @param idempotencyKey          the client-supplied {@code Idempotency-Key} header
     *                                value, or null/blank if none was sent
     * @param correlationId           rides along on the written {@link
     *                                com.subscriptionbilling.audit.AuditLogEntry}
     * @return the Subscription's state after this request — either the just-applied
     *         change, or (for a deduplicated retry) its unchanged current state
     * @throws SubscriptionAccessDeniedException             if the Subscription doesn't
     *         exist or doesn't belong to this Customer
     * @throws PlanUnavailableForSignupException             if the target Plan doesn't
     *         exist, is retired, or has no current price
     * @throws SubscriptionNotEligibleForPlanChangeException if not currently {@code
     *         ACTIVE}
     */
    @Transactional
    public SubscriptionView schedulePlanChange(UUID subscriptionId, UUID authenticatedCustomerId, UUID targetPlanId,
                                                String idempotencyKey, String correlationId) {
        Instant now = Instant.now(clock);
        return applyTransition(subscriptionId, authenticatedCustomerId, idempotencyKey, PLAN_CHANGE_OPERATION,
                subscription -> {
                    PlanSummary targetPlanSummary = planCatalogService.findAvailablePlan(targetPlanId)
                            .orElseThrow(() -> new PlanUnavailableForSignupException(targetPlanId));
                    Plan targetPlan = planRepository.getReferenceById(targetPlanId);
                    boolean targetPlanIsFree = targetPlanSummary.currentPrice().signum() == 0;
                    subscription.schedulePlanChange(targetPlan, targetPlanIsFree, now);
                }, correlationId);
    }

    /**
     * Suspends a Subscription immediately, per {@link Subscription#suspend}'s business
     * rule ("first failed charge suspends, no grace period"). Unlike {@link #cancel}/
     * {@link #undoCancel}, this is system-triggered — there is no authenticated
     * Customer to check ownership against, and no Idempotency-Key. This is the
     * Subscription's only path to {@code suspended}, so it runs exactly once per
     * Invoice — never again for that same Invoice's later Dunning retries, which
     * reschedule or cancel instead — and also writes a {@code PAYMENT_FAILED} {@link
     * OutboxEvent} in the same transaction as the suspension, correlated to {@code
     * correlationId} (the failed Invoice's id).
     *
     * @param subscriptionId the Subscription to suspend
     * @param billingPeriod  the Billing Cycle date whose charge just failed
     * @param correlationId  the failed Invoice's id, rides along on the written {@link
     *                       com.subscriptionbilling.audit.AuditLogEntry} and the
     *                       written {@link OutboxEvent}'s payload
     * @throws SubscriptionNotEligibleForSuspensionException if not currently {@code
     *         trialing} or {@code active}
     */
    @Transactional
    public void suspend(UUID subscriptionId, LocalDate billingPeriod, String correlationId) {
        Subscription subscription = subscriptionRepository.findById(subscriptionId)
                .orElseThrow(() -> new IllegalStateException("Subscription " + subscriptionId + " does not exist"));
        SubscriptionState oldState = subscription.getState();
        subscription.suspend(billingPeriod);
        subscriptionRepository.saveAndFlush(subscription);
        auditLogEntryRepository.append(new AuditLogEntry(
                UUID.randomUUID(), subscription.getId(), ActorType.SYSTEM, oldState.name(),
                subscription.getState().name(), correlationId));
        outboxEventRepository.save(new OutboxEvent(UUID.randomUUID(), "PAYMENT_FAILED",
                failureAlertPayload(subscriptionId, billingPeriod, correlationId)));
    }

    /**
     * Cancels a {@code suspended} Subscription whose Dunning retries are exhausted
     * (the 3rd retry's failure, scheduled or self-service), per {@link
     * Subscription#cancel()}'s {@code suspended -> canceled} edge. No authenticated
     * Customer, no Idempotency-Key, regardless of trigger.
     *
     * @param subscriptionId the Subscription to cancel
     * @param correlationId  rides along on the written {@link
     *                       com.subscriptionbilling.audit.AuditLogEntry}
     * @param trigger        who the exhausting attempt was triggered by, attributing the
     *                       written {@link com.subscriptionbilling.audit.AuditLogEntry}
     *                       {@code ActorType.CUSTOMER} for a self-service retry or
     *                       {@code ActorType.SYSTEM} for the billing job's scheduled loop
     * @throws SubscriptionAlreadyPendingCancellationException if a cancellation is
     *         already pending
     * @throws SubscriptionAlreadyCanceledException            if already {@code canceled}
     */
    @Transactional
    public void cancelForDunningExhaustion(UUID subscriptionId, String correlationId, ChargeTrigger trigger) {
        Subscription subscription = subscriptionRepository.findById(subscriptionId)
                .orElseThrow(() -> new IllegalStateException("Subscription " + subscriptionId + " does not exist"));
        SubscriptionState oldState = subscription.getState();
        subscription.cancel();
        subscriptionRepository.saveAndFlush(subscription);
        ActorType actorType = trigger == ChargeTrigger.CUSTOMER ? ActorType.CUSTOMER : ActorType.SYSTEM;
        auditLogEntryRepository.append(new AuditLogEntry(
                UUID.randomUUID(), subscription.getId(), actorType, oldState.name(),
                subscription.getState().name(), correlationId));
    }

    /**
     * Cancels a Subscription directly in response to a disputed charge, per {@link
     * Subscription#cancelForDispute()}'s {@code active -> canceled} and {@code
     * suspended -> canceled} edges. Gateway-triggered, like {@link #suspend} is
     * system-triggered: no authenticated Customer, no Idempotency-Key — redelivery
     * safety for the triggering webhook event is the caller's responsibility (event-id
     * deduplication), not this method's.
     *
     * <p>Already {@code canceled} is a safe no-op: returns without writing a second
     * {@link com.subscriptionbilling.audit.AuditLogEntry} or touching persistence, so a
     * dispute racing an unrelated cancellation trigger for the same Subscription never
     * conflicts. Canceling directly from {@code suspended} also fails that Billing
     * Cycle's still-open Invoice, via {@link InvoiceCancellationPort#failStillOpenInvoice}
     * — same reasoning as {@link #cancel}'s suspended-origin handling.
     *
     * @param subscriptionId the Subscription to cancel
     * @param correlationId  the triggering webhook event's id, rides along on the
     *                       written {@link com.subscriptionbilling.audit.AuditLogEntry},
     *                       attributed {@code ActorType.GATEWAY}
     * @throws SubscriptionNotEligibleForDisputeCancellationException if currently
     *         {@code trialing} or {@code pending_cancellation}
     */
    @Transactional
    public void cancelForDispute(UUID subscriptionId, String correlationId) {
        Subscription subscription = subscriptionRepository.findById(subscriptionId)
                .orElseThrow(() -> new IllegalStateException("Subscription " + subscriptionId + " does not exist"));
        SubscriptionState oldState = subscription.getState();
        if (oldState == SubscriptionState.CANCELED) {
            return;
        }

        LocalDate stillOpenBillingPeriod = subscription.getDunningBillingPeriod();
        subscription.cancelForDispute();
        subscriptionRepository.saveAndFlush(subscription);
        auditLogEntryRepository.append(new AuditLogEntry(
                UUID.randomUUID(), subscription.getId(), ActorType.GATEWAY, oldState.name(),
                subscription.getState().name(), correlationId));
        if (stillOpenBillingPeriod != null) {
            invoiceCancellationPort.failStillOpenInvoice(subscriptionId, stillOpenBillingPeriod);
        }
    }

    /**
     * Moves a Subscription's {@code due_date} to {@code retryDueDate}, per {@link
     * Subscription#scheduleRetry}. System-triggered, like {@link #suspend}: no
     * authenticated Customer, no Idempotency-Key. Unlike {@link #suspend}, no
     * AuditLogEntry is written, since {@code state} does not change.
     *
     * @param subscriptionId the Subscription to schedule the retry for
     * @param retryDueDate   the next Dunning retry date
     */
    @Transactional
    public void scheduleRetry(UUID subscriptionId, LocalDate retryDueDate) {
        Subscription subscription = subscriptionRepository.findById(subscriptionId)
                .orElseThrow(() -> new IllegalStateException("Subscription " + subscriptionId + " does not exist"));
        subscription.scheduleRetry(retryDueDate);
        subscriptionRepository.saveAndFlush(subscription);
    }

    /**
     * Charges a {@code suspended} Subscription's card-on-file synchronously as a
     * self-service Dunning retry, reusing {@link DunningRetryCharge} — the exact same
     * charge/record/hand-off path {@code BillingJobRunner}'s scheduled day 1/3/7 loop
     * drives — so this consumes one of the same bounded retry slots and can never
     * record different bookkeeping than a scheduled retry. A resulting {@code
     * suspended -> active} or {@code suspended -> canceled} transition (and its audit
     * entry) is already committed by the port implementations {@link DunningRetryCharge}
     * calls through by the time this method returns; a business decline that neither
     * exhausts nor recovers is left {@code suspended} with its retry counter
     * incremented, not treated as a rejection.
     *
     * <p>Deliberately not {@code @Transactional}: {@link DunningRetryCharge#attempt}
     * calls the payment gateway, and this module's rule against external calls inside a
     * database transaction applies here exactly as it does to the billing job's own
     * call to the same method. The caller must fetch the Subscription's resulting state
     * itself (e.g. via {@link #getOwnSubscription}) — this method reports only whether
     * the attempt itself was rejected before any charge was made.
     *
     * @param subscriptionId          the Subscription to retry
     * @param authenticatedCustomerId the Customer the caller's bearer token identifies
     * @param idempotencyKey          the client-supplied {@code Idempotency-Key} header
     *                                value, or null/blank if none was sent
     * @param correlationId           the request's correlation id, carried onto a
     *                                resulting recovery's {@link
     *                                com.subscriptionbilling.audit.AuditLogEntry},
     *                                attributed {@code ActorType.CUSTOMER}
     * @throws SubscriptionAccessDeniedException  if the Subscription doesn't exist or
     *         doesn't belong to this Customer
     * @throws SubscriptionNotSuspendedException  if not currently {@code suspended}
     * @throws PaymentGatewayUnavailableException if the gateway attempt fails
     *         transiently — never thrown for a business decline, which is a normal
     *         recorded outcome, not a rejection
     */
    public void retryPayment(UUID subscriptionId, UUID authenticatedCustomerId, String idempotencyKey,
                              String correlationId) {
        Subscription subscription = ownedSubscription(subscriptionId, authenticatedCustomerId);
        boolean hasIdempotencyKey = StringUtils.hasText(idempotencyKey);
        if (hasIdempotencyKey
                && !idempotencyService.recordIfNew(authenticatedCustomerId, RETRY_PAYMENT_OPERATION, idempotencyKey)) {
            return;
        }
        if (subscription.getState() != SubscriptionState.SUSPENDED) {
            releaseIfPresent(hasIdempotencyKey, authenticatedCustomerId, idempotencyKey);
            throw new SubscriptionNotSuspendedException(subscriptionId, subscription.getState());
        }

        ChargeableSubscription chargeable = chargeableSubscriptionPort.loadForCharge(subscriptionId);
        DunningRetryChargeResult result = dunningRetryCharge.attempt(
                subscriptionId, chargeable, Instant.now(clock), correlationId, ChargeTrigger.CUSTOMER);
        if (result instanceof DunningRetryChargeResult.FailedTransiently transientFailure) {
            releaseIfPresent(hasIdempotencyKey, authenticatedCustomerId, idempotencyKey);
            throw new PaymentGatewayUnavailableException(transientFailure.reason());
        }
    }

    private void releaseIfPresent(boolean hasIdempotencyKey, UUID authenticatedCustomerId, String idempotencyKey) {
        if (hasIdempotencyKey) {
            idempotencyService.release(authenticatedCustomerId, RETRY_PAYMENT_OPERATION, idempotencyKey);
        }
    }

    /**
     * Records a trial-ending-soon notification for a trialing Subscription approaching
     * its Trial end, driven by the billing job's daily scan. System-triggered, like
     * {@link #suspend}: no authenticated Customer, no Idempotency-Key. No {@link
     * com.subscriptionbilling.audit.AuditLogEntry} is written, since {@code state} does
     * not change — same reasoning as {@link #scheduleRetry}. The scan's own candidate
     * query is the guard against re-notifying on a later run, not anything checked here.
     *
     * @param subscriptionId the Subscription to notify
     * @param notifiedAt     the instant this notification was enqueued, recorded via
     *                       {@link Subscription#markTrialEndingSoonNotified} and carried
     *                       in the written {@link OutboxEvent}'s payload
     */
    @Transactional
    public void notifyTrialEndingSoon(UUID subscriptionId, Instant notifiedAt) {
        Subscription subscription = subscriptionRepository.findById(subscriptionId)
                .orElseThrow(() -> new IllegalStateException("Subscription " + subscriptionId + " does not exist"));
        subscription.markTrialEndingSoonNotified(notifiedAt);
        subscriptionRepository.saveAndFlush(subscription);
        outboxEventRepository.save(new OutboxEvent(UUID.randomUUID(), "TRIAL_ENDING_SOON",
                trialEndingSoonPayload(subscriptionId, subscription.getTrialEndsAt())));
    }

    /**
     * Shared skeleton behind {@link #cancel} and {@link #undoCancel}.
     *
     * <p>An {@code idempotencyKey} is recorded (committed, independent of this method's
     * own transaction) <em>before</em> {@code transition} runs, since that's the only
     * way to close the race between two concurrent requests. If {@code transition} then
     * rejects the request (e.g. canceling an already-{@code canceled} Subscription),
     * the just-recorded key is released before the rejection propagates — otherwise a
     * legitimate retry of that same failing request, reusing the same key, would find
     * the key already recorded and be wrongly short-circuited into a fabricated success
     * instead of seeing the same rejection again.
     */
    private SubscriptionView applyTransition(UUID subscriptionId, UUID authenticatedCustomerId, String idempotencyKey,
                                               String operation, Consumer<Subscription> transition,
                                               String correlationId) {
        Subscription subscription = ownedSubscription(subscriptionId, authenticatedCustomerId);
        boolean hasIdempotencyKey = StringUtils.hasText(idempotencyKey);
        if (hasIdempotencyKey && !idempotencyService.recordIfNew(authenticatedCustomerId, operation, idempotencyKey)) {
            return SubscriptionView.from(subscription);
        }

        try {
            SubscriptionState oldState = subscription.getState();
            transition.accept(subscription);
            // Flushed explicitly (rather than left to commit-time, after this method
            // returns) so a lost optimistic-lock race — Subscription's @Version field —
            // throws here, inside this try block, instead of at the transactional
            // proxy's commit boundary where the catch below could never see it.
            subscriptionRepository.saveAndFlush(subscription);
            auditLogEntryRepository.append(new AuditLogEntry(
                    UUID.randomUUID(), subscription.getId(), ActorType.CUSTOMER, oldState.name(),
                    subscription.getState().name(), correlationId));
            return SubscriptionView.from(subscription);
        } catch (RuntimeException transitionFailed) {
            if (hasIdempotencyKey) {
                idempotencyService.release(authenticatedCustomerId, operation, idempotencyKey);
            }
            throw transitionFailed;
        }
    }

    private static String failureAlertPayload(UUID subscriptionId, LocalDate billingPeriod, String invoiceId) {
        return "{\"subscriptionId\":\"" + subscriptionId + "\",\"billingPeriod\":\"" + billingPeriod
                + "\",\"invoiceId\":\"" + invoiceId + "\"}";
    }

    private static String cancellationConfirmedPayload(UUID subscriptionId, UUID customerId) {
        return "{\"subscriptionId\":\"" + subscriptionId + "\",\"customerId\":\"" + customerId + "\"}";
    }

    private static String trialEndingSoonPayload(UUID subscriptionId, Instant trialEndsAt) {
        return "{\"subscriptionId\":\"" + subscriptionId + "\",\"trialEndsAt\":\"" + trialEndsAt + "\"}";
    }

    private Subscription ownedSubscription(UUID subscriptionId, UUID authenticatedCustomerId) {
        return subscriptionRepository.findById(subscriptionId)
                .filter(candidate -> candidate.getCustomer().getId().equals(authenticatedCustomerId))
                .orElseThrow(() -> new SubscriptionAccessDeniedException(subscriptionId));
    }

    private Customer resolveCustomer(SignupCommand command) {
        if (command.existingCustomerId() != null) {
            // A bearer token was presented at signup: the identity-bootstrap exception
            // for a re-subscribing Customer, so this Subscription attaches to
            // the Customer the token already identifies rather than minting a second
            // Customer record. A token we issued pointing at no Customer record would be
            // a system consistency bug, not a client error — left to the generic 500
            // handler rather than modeled as a client-facing exception.
            return customerRepository.findById(command.existingCustomerId())
                    .orElseThrow(() -> new IllegalStateException(
                            "JWT identified Customer " + command.existingCustomerId() + " has no matching record"));
        }
        return customerRepository.save(new Customer(UUID.randomUUID(), command.email()));
    }
}
