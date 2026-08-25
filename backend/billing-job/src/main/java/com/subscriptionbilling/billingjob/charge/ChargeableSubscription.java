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
 * @param billingPeriod      the Billing Cycle date being charged for (the Subscription's due date at selection time)
 * @param anchorDayOfMonth   the Subscription's fixed Anchor Date day-of-month, source of {@link
 *                           com.subscriptionbilling.billingjob.anchor.AnchorDate}'s clamping for the next cycle
 */
public record ChargeableSubscription(UUID subscriptionId, String paymentMethodToken, BigDecimal amount,
                                      UUID priceVersionId, LocalDate billingPeriod, int anchorDayOfMonth) {
}
