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

    public Instant getCreatedAt() {
        return createdAt;
    }
}
