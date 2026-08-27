package com.subscriptionbilling.billingcore.subscription;

import com.subscriptionbilling.billingcore.plan.PriceVersion;
import com.subscriptionbilling.billingcore.plan.PriceVersionRepository;
import com.subscriptionbilling.billingjob.charge.ChargeableSubscription;
import com.subscriptionbilling.billingjob.charge.ChargeableSubscriptionPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * This module's implementation of the billing-job module's {@link
 * ChargeableSubscriptionPort} contract: resolves a due Subscription's card-on-file
 * token, the {@link PriceVersion} in effect for its Plan on the Billing Cycle date being
 * charged, and the Anchor Date day-of-month a successful charge's next cycle clamps
 * against. For an already-paid Subscription (a renewal) that's the fixed day {@link
 * Subscription#getBillingCycleAnchor()} already carries; for a {@code trialing}
 * Subscription converting today (no Billing Cycle opened yet — Invariant 5), it's
 * today's day-of-month, the Anchor Date about to be established by this same charge.
 *
 * <p>A {@code suspended} Subscription is a due Dunning retry, not a fresh renewal: its
 * {@code due_date} has been repurposed to hold the next scheduled retry date (see
 * {@link Subscription#scheduleRetry}), so the Billing Cycle actually being charged for
 * is read from {@link Subscription#getDunningBillingPeriod()} instead — the field
 * {@link Subscription#suspend} preserved it in — and the assembled {@link
 * ChargeableSubscription#dunningRetry()} flag is set, telling the billing job which
 * outcome handling applies.
 */
@Component
public class ChargeableSubscriptionAdapter implements ChargeableSubscriptionPort {

    private final SubscriptionRepository subscriptionRepository;
    private final PriceVersionRepository priceVersionRepository;
    private final Clock clock;

    @Autowired
    public ChargeableSubscriptionAdapter(SubscriptionRepository subscriptionRepository,
                                          PriceVersionRepository priceVersionRepository) {
        this(subscriptionRepository, priceVersionRepository, Clock.systemUTC());
    }

    ChargeableSubscriptionAdapter(SubscriptionRepository subscriptionRepository,
                                   PriceVersionRepository priceVersionRepository, Clock clock) {
        this.subscriptionRepository = subscriptionRepository;
        this.priceVersionRepository = priceVersionRepository;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public ChargeableSubscription loadForCharge(UUID subscriptionId) {
        Subscription subscription = subscriptionRepository.findById(subscriptionId).orElseThrow(() -> new IllegalStateException("Subscription " + subscriptionId + " does not exist"));
        boolean dunningRetry = subscription.getState() == SubscriptionState.SUSPENDED;
        LocalDate billingPeriod = dunningRetry ? subscription.getDunningBillingPeriod() : subscription.getDueDate();

        UUID planId = subscription.getPlan().getId();
        // Priced as of the billing period being charged, not as of "now": a Plan price
        // change never retroactively alters an already-due cycle, including one caught
        // up several days late by a missed run.
        PriceVersion effectivePrice = priceVersionRepository
                .findTopByPlanIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(
                        planId, billingPeriod.atStartOfDay(ZoneOffset.UTC).toInstant())
                .orElseThrow(() -> new IllegalStateException("Plan " + planId + " has no PriceVersion in effect"));
        int anchorDayOfMonth = subscription.getBillingCycleAnchor() != null
                ? subscription.getBillingCycleAnchor().atZone(ZoneOffset.UTC).getDayOfMonth()
                : LocalDate.now(clock).getDayOfMonth();

        return new ChargeableSubscription(
                subscription.getId(),
                subscription.getCustomer().getPaymentMethodToken(),
                effectivePrice.getAmount(),
                effectivePrice.getId(),
                billingPeriod,
                anchorDayOfMonth,
                dunningRetry);
    }
}
