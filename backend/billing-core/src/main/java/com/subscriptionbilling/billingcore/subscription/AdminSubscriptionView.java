package com.subscriptionbilling.billingcore.subscription;

import com.subscriptionbilling.billingcore.plan.Plan;

import java.time.Instant;
import java.util.UUID;

/**
 * Read model for a Subscription fetched by an Admin rather than its owning
 * Customer — a sibling of {@link SubscriptionView}, not a replacement: that record's
 * contract ("fetched by its owning Customer") stays exactly as documented, since
 * {@code getOwnSubscription} is unchanged. This adds {@code customerId}, which the
 * Customer-facing view has no reason to carry (a Customer fetching their own
 * Subscription already knows whose it is).
 */
public record AdminSubscriptionView(UUID id, UUID customerId, SubscriptionState state, SubscriptionView.PlanRef plan,
                                     SubscriptionView.PlanRef pendingPlanChange, Instant trialEndsAt,
                                     Instant billingCycleAnchor) {

    static AdminSubscriptionView from(Subscription subscription) {
        Plan pendingPlanChange = subscription.getPendingPlanChange();
        return new AdminSubscriptionView(
                subscription.getId(),
                subscription.getCustomer().getId(),
                subscription.getState(),
                SubscriptionView.PlanRef.from(subscription.getPlan()),
                pendingPlanChange != null ? SubscriptionView.PlanRef.from(pendingPlanChange) : null,
                subscription.getTrialEndsAt(),
                subscription.getBillingCycleAnchor());
    }
}
