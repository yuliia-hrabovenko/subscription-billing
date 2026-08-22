package com.subscriptionbilling.billingcore.subscription;

import com.subscriptionbilling.audit.ActorType;
import com.subscriptionbilling.audit.AuditLogEntry;
import com.subscriptionbilling.audit.AuditLogEntryRepository;
import com.subscriptionbilling.billingcore.auth.CustomerTokenIssuer;
import com.subscriptionbilling.billingcore.customer.Customer;
import com.subscriptionbilling.billingcore.customer.CustomerRepository;
import com.subscriptionbilling.billingcore.idempotency.IdempotencyService;
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
     * Creates a Subscription for a prospective or re-subscribing Customer, taking
     * exactly one of three paths based on the target Plan's price and {@code
     * command.useTrial()}:
     *
     * <ul>
     *     <li>Free Plan (price is zero) — the {@code [*] -> active} edge, no Trial, no
     *     Billing Cycle (a free Subscription never has one).</li>
     *     <li>Paid Plan, Trial opted in — the {@code [*] -> trialing} edge, no Billing
     *     Cycle yet (nothing is charged until the Trial converts).</li>
     *     <li>Paid Plan, no Trial — the {@code [*] -> active} edge with a Billing Cycle
     *     anchored to now. No charge is actually performed by this method: money
     *     movement is a separate concern that acts on the domain state this method
     *     establishes.</li>
     * </ul>
     *
     * <p>Before creating anything, this method enforces two rules: the target Plan
     * must be available for signup (exists, not retired, has a current price), and — if
     * the request identified an existing Customer via a bearer token — that Customer
     * must not already hold a non-{@code canceled} Subscription (a Customer may have at
     * most one at a time; re-subscribing after a cancellation is fine and always
     * produces a brand-new Subscription row with fresh Trial eligibility, never a
     * reactivated old one). A paid-Plan signup additionally requires {@code
     * command.paymentMethodToken()} to be present, for either of its two paths.
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
     * Cancels the Customer's own Subscription — see {@link Subscription#cancel()} for
     * which originating state produces immediate vs. deferred termination, and for the
     * exceptions thrown when a cancellation can't be applied.
     *
     * <p>{@code idempotencyKey}, when present, is recorded against this Customer and
     * the {@code "cancel"} operation before the transition is attempted: a request
     * reusing a key already recorded within the retention window is treated as an
     * already-applied retry and short-circuits to the Subscription's current state
     * without re-running {@link Subscription#cancel()} or writing a second {@link
     * com.subscriptionbilling.audit.AuditLogEntry}. A blank or absent key skips
     * deduplication entirely — every such request is attempted as a fresh transition.
     *
     * @param subscriptionId         the Subscription to cancel
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
     * Reverses the Customer's own deferred cancellation — see {@link
     * Subscription#undoCancel()} for the single originating state this requires and the
     * exception thrown otherwise. Idempotency handling mirrors {@link #cancel}: a
     * repeated request with an already-recorded key short-circuits to the
     * Subscription's current state instead of re-running the transition.
     *
     * @param subscriptionId         the Subscription to restore to {@code active}
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
     * Shared skeleton behind {@link #cancel} and {@link #undoCancel}: look up the
     * Customer's own Subscription, short-circuit a deduplicated retry, otherwise apply
     * {@code transition} and record it. Both callers differ only in which domain method
     * runs and which {@link IdempotencyService} operation name scopes its dedup key —
     * everything else (ownership enforcement, the dedup short-circuit, and the
     * old-state/new-state audit write) is identical, so it lives here once.
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
