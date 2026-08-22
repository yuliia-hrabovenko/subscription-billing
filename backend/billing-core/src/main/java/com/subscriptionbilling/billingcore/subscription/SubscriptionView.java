package com.subscriptionbilling.billingcore.subscription;

import com.subscriptionbilling.billingcore.plan.Plan;

import java.time.Instant;
import java.util.UUID;

/**
 * Read model for a Subscription fetched by its owning Customer — other modules consume
 * this instead of the {@link Subscription} JPA entity directly. {@code trialEndsAt} and
 * {@code billingCycleAnchor} mirror {@link Subscription}'s own fields: at most one is
 * non-null.
 */
public record SubscriptionView(UUID id, SubscriptionState state, PlanRef plan, PlanRef pendingPlanChange,
                                Instant trialEndsAt, Instant billingCycleAnchor) {

    public record PlanRef(UUID id, String code, String name) {

        static PlanRef from(Plan plan) {
            return new PlanRef(plan.getId(), plan.getCode(), plan.getName());
        }
    }

    static SubscriptionView from(Subscription subscription) {
        Plan pendingPlanChange = subscription.getPendingPlanChange();
        return new SubscriptionView(
                subscription.getId(),
                subscription.getState(),
                PlanRef.from(subscription.getPlan()),
                pendingPlanChange != null ? PlanRef.from(pendingPlanChange) : null,
                subscription.getTrialEndsAt(),
                subscription.getBillingCycleAnchor());
    }
}
