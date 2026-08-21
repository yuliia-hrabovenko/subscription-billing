package com.subscriptionbilling.api.subscription;

import com.subscriptionbilling.billingcore.subscription.SubscriptionView;

import java.util.UUID;

/**
 * {@code GET /api/v1/subscriptions/{id}}'s response. {@code billingCycle} is always
 * {@code null} for now — untyped rather than modeled, because the concept it would
 * represent has no domain/schema representation yet (Billing Cycle only exists once a
 * paid-plan signup path lands); this field exists purely so the wire
 * contract is already in its final shape.
 */
public record SubscriptionResponse(UUID id, String state, PlanRef plan, PlanRef pendingPlanChange, Object billingCycle) {

    record PlanRef(UUID id, String code, String name) {

        static PlanRef from(SubscriptionView.PlanRef planRef) {
            return planRef != null ? new PlanRef(planRef.id(), planRef.code(), planRef.name()) : null;
        }
    }

    static SubscriptionResponse from(SubscriptionView view) {
        return new SubscriptionResponse(
                view.id(), view.state().name(), PlanRef.from(view.plan()), PlanRef.from(view.pendingPlanChange()), null);
    }
}
