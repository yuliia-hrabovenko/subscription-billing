package com.subscriptionbilling.billingcore.subscription;

import com.subscriptionbilling.billingcore.plan.Plan;

import java.util.UUID;

/**
 * Read model for a Subscription fetched by its owning Customer — other modules (e.g.
 * {@code api}'s controller) consume this instead of the {@link Subscription} JPA entity
 * directly, same reasoning as {@link com.subscriptionbilling.billingcore.plan.PlanSummary}
 * for the Plan catalog. Deliberately has no Billing Cycle field: that concept has no
 * schema representation yet (this ticket only implements the free-plan path -
 * {@code api}'s response DTO is what always renders it as
 * {@code null} for now, since there's nothing here to render it from.
 */
public record SubscriptionView(UUID id, SubscriptionState state, PlanRef plan, PlanRef pendingPlanChange) {

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
                pendingPlanChange != null ? PlanRef.from(pendingPlanChange) : null);
    }
}
