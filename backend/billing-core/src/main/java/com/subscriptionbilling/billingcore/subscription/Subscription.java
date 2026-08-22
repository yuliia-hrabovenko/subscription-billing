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
