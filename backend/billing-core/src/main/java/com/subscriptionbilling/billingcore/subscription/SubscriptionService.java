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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
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

    private final CustomerRepository customerRepository;
    private final PlanRepository planRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final PlanCatalogService planCatalogService;
    private final AuditLogEntryRepository auditLogEntryRepository;
    private final CustomerTokenIssuer tokenIssuer;
    private final IdempotencyService idempotencyService;
    private final Clock clock;

    @Autowired
    public SubscriptionService(CustomerRepository customerRepository, PlanRepository planRepository,
                                SubscriptionRepository subscriptionRepository, PlanCatalogService planCatalogService,
                                AuditLogEntryRepository auditLogEntryRepository, CustomerTokenIssuer tokenIssuer,
                                IdempotencyService idempotencyService) {
        this(customerRepository, planRepository, subscriptionRepository, planCatalogService, auditLogEntryRepository,
                tokenIssuer, idempotencyService, Clock.systemUTC());
    }

    SubscriptionService(CustomerRepository customerRepository, PlanRepository planRepository,
                         SubscriptionRepository subscriptionRepository, PlanCatalogService planCatalogService,
                         AuditLogEntryRepository auditLogEntryRepository, CustomerTokenIssuer tokenIssuer,
                         IdempotencyService idempotencyService, Clock clock) {
        this.customerRepository = customerRepository;
        this.planRepository = planRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.planCatalogService = planCatalogService;
        this.auditLogEntryRepository = auditLogEntryRepository;
        this.tokenIssuer = tokenIssuer;
        this.idempotencyService = idempotencyService;
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
     * See {@link Subscription#cancel()} for which originating state produces immediate
     * vs. deferred termination. A blank or absent {@code idempotencyKey} skips
     * deduplication — see {@link #applyTransition} for the dedup/release contract.
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
        return applyTransition(subscriptionId, authenticatedCustomerId, idempotencyKey, CANCEL_OPERATION,
                Subscription::cancel, correlationId);
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
     * Customer to check ownership against, and no Idempotency-Key.
     *
     * @param subscriptionId the Subscription to suspend
     * @param billingPeriod  the Billing Cycle date whose charge just failed
     * @param correlationId  rides along on the written {@link
     *                       com.subscriptionbilling.audit.AuditLogEntry}
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
    }

    /**
     * Cancels a {@code suspended} Subscription whose Dunning retries are exhausted
     * (the 3rd scheduled retry's failure), per {@link Subscription#cancel()}'s {@code
     * suspended -> canceled} edge. System-triggered, like {@link #suspend}: no
     * authenticated Customer, no Idempotency-Key.
     *
     * @param subscriptionId the Subscription to cancel
     * @param correlationId  rides along on the written {@link
     *                       com.subscriptionbilling.audit.AuditLogEntry}
     * @throws SubscriptionAlreadyPendingCancellationException if a cancellation is
     *         already pending
     * @throws SubscriptionAlreadyCanceledException            if already {@code canceled}
     */
    @Transactional
    public void cancelForDunningExhaustion(UUID subscriptionId, String correlationId) {
        Subscription subscription = subscriptionRepository.findById(subscriptionId)
                .orElseThrow(() -> new IllegalStateException("Subscription " + subscriptionId + " does not exist"));
        SubscriptionState oldState = subscription.getState();
        subscription.cancel();
        subscriptionRepository.saveAndFlush(subscription);
        auditLogEntryRepository.append(new AuditLogEntry(
                UUID.randomUUID(), subscription.getId(), ActorType.SYSTEM, oldState.name(),
                subscription.getState().name(), correlationId));
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
