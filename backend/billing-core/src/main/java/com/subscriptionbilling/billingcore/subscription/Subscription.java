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
 * <p>A Subscription only ever holds one of {@link #trialEndsAt} or {@link
 * #billingCycleAnchor} at a time, never both, and both are null for a free Subscription:
 * a Trial has no Billing Cycle yet (nothing has been charged), and a Billing Cycle only
 * exists once a Subscription is actually paying (free or trialing, neither). {@link
 * #startTrial} and {@link #startPaidImmediately} are the only two ways to create a
 * Subscription with either field populated, so this pairing can't drift apart by
 * accident.
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
     * Optimistic-locking guard: {@link #cancel()} and {@link #undoCancel()} are the
     * first Customer-triggered mutations of an already-persisted Subscription (every
     * earlier write was an insert-only signup). Two concurrent requests without a
     * shared {@code Idempotency-Key} (idempotency dedup is opt-in, not a substitute for
     * this) would otherwise both read the same pre-transition state and both commit,
     * double-applying a transition and writing two {@link
     * com.subscriptionbilling.audit.AuditLogEntry} rows for one logical action. The
     * second writer instead fails fast with an optimistic-lock exception.
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
     * The {@code [*] -> trialing} edge: a paid-Plan signup that opted into a Trial
     * instead of being charged immediately. Sets {@link #trialUsed} on this instance
     * (a fresh Subscription always starts with it {@code false}, regardless of whether
     * an earlier, now-{@code canceled} Subscription for the same Customer ever ran one)
     * and leaves {@link #billingCycleAnchor} null, since nothing is charged — and so no
     * Billing Cycle exists — until the Trial converts.
     *
     * @param id         the new Subscription's identity
     * @param customer   the Customer signing up
     * @param plan       the paid Plan being trialed
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
     * The {@code [*] -> active} edge for a paid Plan with no Trial: access and the
     * Billing Cycle both start now, anchored to the moment this method runs. Money
     * movement (the actual first charge) is a separate concern owned elsewhere — this
     * method only establishes the domain-model state that charge will eventually act on.
     *
     * @param id                  the new Subscription's identity
     * @param customer            the Customer signing up
     * @param plan                the paid Plan being subscribed to
     * @param billingCycleAnchor  the instant the Billing Cycle starts (and recurs from,
     *                            once a billing schedule exists)
     * @return a new Subscription in {@link SubscriptionState#ACTIVE} with a Billing
     *         Cycle already anchored
     */
    public static Subscription startPaidImmediately(UUID id, Customer customer, Plan plan, Instant billingCycleAnchor) {
        Subscription subscription = new Subscription(id, customer, plan, SubscriptionState.ACTIVE);
        subscription.billingCycleAnchor = billingCycleAnchor;
        return subscription;
    }

    /**
     * Cancels this Subscription, choosing immediate vs. deferred termination by the
     * state canceled from — the state-machine edges a Customer-initiated cancel can
     * take, per Invariant 8 ({@code pending_cancellation} reachable only from {@code
     * active}):
     *
     * <ul>
     *     <li>{@code trialing} or {@code suspended} → {@code canceled} immediately:
     *     neither has paid access worth protecting — a Trial hasn't been charged yet,
     *     and a suspended Subscription's access is already cut off. Any {@link
     *     #trialEndsAt} is cleared, since a {@code canceled} Subscription never carries
     *     one.</li>
     *     <li>{@code active} with a Billing Cycle ({@link #billingCycleAnchor} set) →
     *     {@code pending_cancellation}: access continues through the Billing Cycle
     *     already paid for; the state doesn't reach {@code canceled} until that period
     *     ends (or is undone first via {@link #undoCancel()}).</li>
     *     <li>{@code active} with no Billing Cycle (a free-Plan Subscription) → {@code
     *     canceled} immediately: there's no paid period to defer to, and nothing ever
     *     advances a free Subscription out of {@code pending_cancellation} — deferring
     *     here would strand it there permanently.</li>
     * </ul>
     *
     * @throws SubscriptionAlreadyPendingCancellationException if a cancellation is
     *         already pending
     * @throws SubscriptionAlreadyCanceledException            if already {@code canceled}
     *         (terminal, Invariant 7)
     */
    public void cancel() {
        switch (state) {
            case TRIALING, SUSPENDED -> {
                state = SubscriptionState.CANCELED;
                trialEndsAt = null;
            }
            case ACTIVE -> state = billingCycleAnchor != null
                    ? SubscriptionState.PENDING_CANCELLATION
                    : SubscriptionState.CANCELED;
            case PENDING_CANCELLATION -> throw new SubscriptionAlreadyPendingCancellationException(id);
            case CANCELED -> throw new SubscriptionAlreadyCanceledException(id);
            // A plain switch statement over an enum isn't exhaustiveness-checked by the
            // compiler the way a switch expression is: without this arm, a future
            // SubscriptionState value added here but missed above would silently no-op
            // instead of failing loudly — same reasoning as the "system consistency bug,
            // not a client error" IllegalStateException in SubscriptionService.resolveCustomer.
            default -> throw new IllegalStateException("Unhandled Subscription state for cancel(): " + state);
        }
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
