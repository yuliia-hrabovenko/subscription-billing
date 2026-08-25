package com.subscriptionbilling.billingcore.subscription;

import com.subscriptionbilling.billingcore.plan.PriceVersion;
import com.subscriptionbilling.billingcore.plan.PriceVersionRepository;
import com.subscriptionbilling.billingjob.charge.ChargeableSubscription;
import com.subscriptionbilling.billingjob.charge.ChargeableSubscriptionPort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneOffset;
import java.util.UUID;

/**
 * This module's implementation of the billing-job module's {@link
 * ChargeableSubscriptionPort} contract: resolves a due Subscription's card-on-file
 * token, the {@link PriceVersion} in effect for its Plan on the Billing Cycle date being
 * charged, and the fixed Anchor Date day-of-month {@link
 * Subscription#getBillingCycleAnchor()} carries.
 */
@Component
public class ChargeableSubscriptionAdapter implements ChargeableSubscriptionPort {

    private final SubscriptionRepository subscriptionRepository;
    private final PriceVersionRepository priceVersionRepository;

    public ChargeableSubscriptionAdapter(SubscriptionRepository subscriptionRepository,
                                          PriceVersionRepository priceVersionRepository) {
        this.subscriptionRepository = subscriptionRepository;
        this.priceVersionRepository = priceVersionRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public ChargeableSubscription loadForCharge(UUID subscriptionId) {
        Subscription subscription = subscriptionRepository.findById(subscriptionId).orElseThrow(() -> new IllegalStateException("Subscription " + subscriptionId + " does not exist"));
        UUID planId = subscription.getPlan().getId();
        // Priced as of the billing period being charged, not as of "now": a Plan price
        // change never retroactively alters an already-due cycle, including one caught
        // up several days late by a missed run.
        PriceVersion effectivePrice = priceVersionRepository
                .findTopByPlanIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(
                        planId, subscription.getDueDate().atStartOfDay(ZoneOffset.UTC).toInstant())
                .orElseThrow(() -> new IllegalStateException("Plan " + planId + " has no PriceVersion in effect"));
        int anchorDayOfMonth = subscription.getBillingCycleAnchor().atZone(ZoneOffset.UTC).getDayOfMonth();

        return new ChargeableSubscription(
                subscription.getId(),
                subscription.getCustomer().getPaymentMethodToken(),
                effectivePrice.getAmount(),
                effectivePrice.getId(),
                subscription.getDueDate(),
                anchorDayOfMonth);
    }
}
