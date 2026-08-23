package com.subscriptionbilling.billingcore.subscription;

import com.subscriptionbilling.billingcore.customer.Customer;
import com.subscriptionbilling.billingcore.plan.Plan;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

/**
 * Ties a Customer to a Plan and carries the lifecycle state.
 *
 * <p>{@link #trialEndsAt} and {@link #billingCycleAnchor} are mutually exclusive and
 * both null for a free Subscription: a Trial has no Billing Cycle yet, and a Billing
 * Cycle only exists once a Subscription is actually paying.
 */
@Entity
@Table(name = "subscription")
public class Subscription {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "plan_id", nullable = false)
    private Plan plan;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pending_plan_id")
    private Plan pendingPlanChange;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SubscriptionState state;

    @Column(name = "trial_used", nullable = false)
    private boolean trialUsed;

    @Column(name = "trial_ends_at")
    private Instant trialEndsAt;

    @Column(name = "billing_cycle_anchor")
    private Instant billingCycleAnchor;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /**
     * Optimistic-locking guard against two concurrent {@link #cancel()}/{@link
     * #undoCancel()} calls (with no shared {@code Idempotency-Key} — dedup is opt-in,
     * not a substitute for this) both committing against the same pre-transition state.
     */
    @Version
    private Long version;

    protected Subscription() {
    }

    public Subscription(UUID id, Customer customer, Plan plan, SubscriptionState state) {
        this.id = id;
        this.customer = customer;
        this.plan = plan;
        this.state = state;
        this.trialUsed = false;
        this.createdAt = Instant.now();
    }

    /**
     * Package-visible test fixture: no production path creates {@code suspended} yet
     * (Dunning owns that), so tests need a way to construct one that inherited a
     * {@link #billingCycleAnchor} from an earlier paid {@code active} life.
     *
     * @param id                 the Subscription's identity
     * @param customer           the owning Customer
     * @param plan               the current Plan
     * @param state              the lifecycle state to construct in
     * @param billingCycleAnchor the Billing Cycle anchor to seed
     */
    Subscription(UUID id, Customer customer, Plan plan, SubscriptionState state, Instant billingCycleAnchor) {
        this(id, customer, plan, state);
        this.billingCycleAnchor = billingCycleAnchor;
    }

    /**
     * The {@code [*] -> trialing} edge. A fresh Subscription always starts with {@link
     * #trialUsed} {@code false} regardless of whether an earlier, now-{@code canceled}
     * Subscription for the same Customer ever ran a Trial. {@link #billingCycleAnchor}
     * stays null: nothing is charged until the Trial converts.
     *
     * @param id          the new Subscription's identity
     * @param customer    the Customer signing up
     * @param plan        the paid Plan being trialed
     * @param trialEndsAt the instant the Trial converts to a paid charge (or is
     *                    canceled first)
     * @return a new Subscription in {@link SubscriptionState#TRIALING}
     */
    public static Subscription startTrial(UUID id, Customer customer, Plan plan, Instant trialEndsAt) {
        Subscription subscription = new Subscription(id, customer, plan, SubscriptionState.TRIALING);
        subscription.trialUsed = true;
        subscription.trialEndsAt = trialEndsAt;
        return subscription;
    }

    /**
     * The {@code [*] -> active} edge for a paid Plan with no Trial. Money movement (the
     * actual first charge) is a separate concern owned elsewhere — this method only
     * establishes the domain-model state that charge will eventually act on.
     *
     * @param id                 the new Subscription's identity
     * @param customer           the Customer signing up
     * @param plan               the paid Plan being subscribed to
     * @param billingCycleAnchor the instant the Billing Cycle starts (and recurs from,
     *                           once a billing schedule exists)
     * @return a new Subscription in {@link SubscriptionState#ACTIVE} with a Billing
     *         Cycle already anchored
     */
    public static Subscription startPaidImmediately(UUID id, Customer customer, Plan plan, Instant billingCycleAnchor) {
        Subscription subscription = new Subscription(id, customer, plan, SubscriptionState.ACTIVE);
        subscription.billingCycleAnchor = billingCycleAnchor;
        return subscription;
    }

    /**
     * Chooses immediate vs. deferred termination by the state canceled from, per
     * Invariant 8 ({@code pending_cancellation} reachable only from {@code active}):
     *
     * <ul>
     *     <li>{@code trialing} or {@code suspended} → {@code canceled} immediately:
     *     neither has paid access worth protecting.</li>
     *     <li>{@code active} with a Billing Cycle → {@code pending_cancellation}:
     *     access continues through the period already paid for.</li>
     *     <li>{@code active} with no Billing Cycle (a free-Plan Subscription) → {@code
     *     canceled} immediately: nothing ever advances a free Subscription out of
     *     {@code pending_cancellation}, so deferring would strand it there.</li>
     * </ul>
     *
     * @throws SubscriptionAlreadyPendingCancellationException if a cancellation is
     *         already pending
     * @throws SubscriptionAlreadyCanceledException            if already {@code canceled}
     *         (terminal, Invariant 7)
     */
    public void cancel() {
        // A switch EXPRESSION assigning directly to state (rather than a switch
        // statement with a hand-written default) gets compiler-enforced exhaustiveness
        // over SubscriptionState for free: a future state added here but missed below
        // fails to compile instead of silently no-op'ing at runtime.
        state = switch (state) {
            case TRIALING, SUSPENDED -> {
                // Both cleared: a canceled Subscription never carries either, regardless
                // of which one this originating state happened to hold.
                trialEndsAt = null;
                billingCycleAnchor = null;
                yield SubscriptionState.CANCELED;
            }
            case ACTIVE -> billingCycleAnchor != null ? SubscriptionState.PENDING_CANCELLATION : SubscriptionState.CANCELED;
            case PENDING_CANCELLATION -> throw new SubscriptionAlreadyPendingCancellationException(id);
            case CANCELED -> throw new SubscriptionAlreadyCanceledException(id);
        };
    }

    /**
     * Reverses a deferred cancellation before it takes effect, restoring full {@code
     * active} access — only reachable from {@code pending_cancellation}, the one state
     * {@link #cancel()} can leave a still-recoverable Subscription in.
     *
     * @throws SubscriptionNotPendingCancellationException if not currently {@code
     *         pending_cancellation}
     */
    public void undoCancel() {
        if (state != SubscriptionState.PENDING_CANCELLATION) {
            throw new SubscriptionNotPendingCancellationException(id, state);
        }
        state = SubscriptionState.ACTIVE;
    }

    /**
     * The paid-to-paid, paid-to-free, and free-to-paid plan-change edges. {@code state}
     * never changes (plan changes aren't part of the state diagram) — only reachable
     * from {@code ACTIVE}, the one state with current paid or free access to change out
     * of.
     *
     * <p>No existing Billing Cycle ({@link #billingCycleAnchor} null, i.e. currently on
     * the free Plan) means free-to-X: applies immediately, moving onto {@code
     * targetPlan} now. A Billing Cycle is opened, anchored to {@code now}, only if
     * {@code targetPlanIsFree} is false — free-to-free must not open one (Invariant 5:
     * a Billing Cycle exists only while on a paid Plan). An existing Billing Cycle
     * (currently paid) means paid-to-paid or paid-to-free: deferred — sets {@link
     * #pendingPlanChange} to {@code targetPlan}, atomically replacing whatever was
     * already pending (Invariant 4). Applying a deferred change at the next Billing
     * Cycle boundary is the Billing Execution Engine's job, out of scope here.
     *
     * @param targetPlan       the Plan being switched to (upgrade, downgrade, or a
     *                         downgrade to Free)
     * @param targetPlanIsFree whether {@code targetPlan}'s current price is zero;
     *                         decides, on the currently-free path only, whether this
     *                         change opens a Billing Cycle
     * @param now              the instant to anchor a new Billing Cycle to, on the
     *                         free-to-paid path only
     * @throws SubscriptionNotEligibleForPlanChangeException if not currently {@code
     *         ACTIVE}
     */
    public void schedulePlanChange(Plan targetPlan, boolean targetPlanIsFree, Instant now) {
        if (state != SubscriptionState.ACTIVE) {
            throw new SubscriptionNotEligibleForPlanChangeException(id, state);
        }
        if (billingCycleAnchor == null) {
            plan = targetPlan;
            if (!targetPlanIsFree) {
                billingCycleAnchor = now;
            }
        } else {
            pendingPlanChange = targetPlan;
        }
    }

    public UUID getId() {
        return id;
    }

    public Customer getCustomer() {
        return customer;
    }

    public Plan getPlan() {
        return plan;
    }

    public Plan getPendingPlanChange() {
        return pendingPlanChange;
    }

    public SubscriptionState getState() {
        return state;
    }

    public boolean isTrialUsed() {
        return trialUsed;
    }

    public Instant getTrialEndsAt() {
        return trialEndsAt;
    }

    public Instant getBillingCycleAnchor() {
        return billingCycleAnchor;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
