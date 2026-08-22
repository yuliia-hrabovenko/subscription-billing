package com.subscriptionbilling.api.subscription;

import com.subscriptionbilling.billingcore.subscription.SubscriptionView;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code GET /api/v1/subscriptions/{id}}'s response. {@code trialEndsAt} is set only
 * while {@code state} is {@code TRIALING}; {@code billingCycle} is set only once the
 * Subscription is on a paid Plan with a Billing Cycle established (immediate-paid
 * signup, or a converted Trial). Both are null for a free-Plan Subscription.
 */
public record SubscriptionResponse(UUID id, String state, PlanRef plan, PlanRef pendingPlanChange,
                                    Instant trialEndsAt, BillingCycleRef billingCycle) {

    record PlanRef(UUID id, String code, String name) {

        static PlanRef from(SubscriptionView.PlanRef planRef) {
            return planRef != null ? new PlanRef(planRef.id(), planRef.code(), planRef.name()) : null;
        }
    }

    static SubscriptionResponse from(SubscriptionView view) {
        return new SubscriptionResponse(
                view.id(), view.state().name(), PlanRef.from(view.plan()), PlanRef.from(view.pendingPlanChange()),
                view.trialEndsAt(), BillingCycleRef.from(view.billingCycleAnchor()));
    }
}
