package com.subscriptionbilling.api.subscription;

import com.subscriptionbilling.billingcore.subscription.AdminSubscriptionView;
import com.subscriptionbilling.billingcore.subscription.SubscriptionView;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code GET /api/v1/admin/subscriptions/{id}}'s response — adds {@code
 * customerId} over {@link SubscriptionResponse}'s shape, since an Admin (unlike a
 * Customer fetching their own Subscription) needs to know whose it is. Public,
 * unlike {@link SubscriptionResponse}'s package-private {@code from}: {@code
 * api.customer}'s {@code CustomerDetailResponse} embeds this to list a Customer's
 * Subscriptions without a second round trip.
 */
public record AdminSubscriptionResponse(UUID id, UUID customerId, String state, PlanRef plan, PlanRef pendingPlanChange,
                                         Instant trialEndsAt, BillingCycleRef billingCycle) {

    public record PlanRef(UUID id, String code, String name) {

        static PlanRef from(SubscriptionView.PlanRef planRef) {
            return planRef != null ? new PlanRef(planRef.id(), planRef.code(), planRef.name()) : null;
        }
    }

    public static AdminSubscriptionResponse from(AdminSubscriptionView view) {
        return new AdminSubscriptionResponse(
                view.id(), view.customerId(), view.state().name(), PlanRef.from(view.plan()),
                PlanRef.from(view.pendingPlanChange()), view.trialEndsAt(), BillingCycleRef.from(view.billingCycleAnchor()));
    }
}
