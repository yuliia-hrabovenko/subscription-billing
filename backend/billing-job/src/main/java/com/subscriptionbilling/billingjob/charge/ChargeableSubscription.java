package com.subscriptionbilling.billingjob.charge;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Everything the billing job needs to attempt one due Subscription's Billing Cycle
 * charge, assembled by {@link ChargeableSubscriptionPort}.
 *
 * @param subscriptionId     the Subscription being charged
 * @param paymentMethodToken the Customer's gateway-provided card-on-file reference
 * @param amount             the amount to charge, from the Plan's current PriceVersion
 * @param priceVersionId     the PriceVersion {@code amount} was read from — snapshotted onto the created Invoice
 * @param billingPeriod      the Billing Cycle date being charged for — the Subscription's due date at
 *                           selection time for an ordinary renewal/Trial-conversion, or the original
 *                           Billing Cycle date a Dunning retry is being retried for (never the retry's
 *                           own scheduled date), so it always identifies the one Invoice this charge's
 *                           Payment Attempt belongs to (Invariant 6)
 * @param anchorDayOfMonth   the Anchor Date day-of-month this cycle clamps the next one against — the
 *                           Subscription's existing one for a renewal or Dunning retry, or today's for a
 *                           Trial converting now (no Billing Cycle opened yet); source of {@link
 *                           com.subscriptionbilling.billingjob.anchor.AnchorDate}'s clamping for the next cycle
 * @param dunningRetry       whether this due charge is a scheduled Dunning retry (the Subscription is
 *                           {@code suspended}) rather than an ordinary renewal or Trial-conversion charge —
 *                           tells the billing job which outcome handling applies
 */
public record ChargeableSubscription(UUID subscriptionId, String paymentMethodToken, BigDecimal amount,
                                      UUID priceVersionId, LocalDate billingPeriod, int anchorDayOfMonth,
                                      boolean dunningRetry) {

    /**
     * Convenience constructor for an ordinary renewal or Trial-conversion charge —
     * never a Dunning retry.
     */
    public ChargeableSubscription(UUID subscriptionId, String paymentMethodToken, BigDecimal amount,
                                   UUID priceVersionId, LocalDate billingPeriod, int anchorDayOfMonth) {
        this(subscriptionId, paymentMethodToken, amount, priceVersionId, billingPeriod, anchorDayOfMonth, false);
    }
}
