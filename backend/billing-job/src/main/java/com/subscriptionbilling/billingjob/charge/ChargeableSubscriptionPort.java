package com.subscriptionbilling.billingjob.charge;

import java.util.UUID;

/**
 * The seam the billing job loads a due Subscription's charge details through, so it
 * never depends on billing-core's Subscription/Plan/PriceVersion persistence directly.
 */
public interface ChargeableSubscriptionPort {

    /**
     * @param subscriptionId a due Subscription's id, as selected by {@link
     *                       com.subscriptionbilling.billingjob.due.DueSubscriptionsPort}
     * @return the details needed to attempt this cycle's charge
     */
    ChargeableSubscription loadForCharge(UUID subscriptionId);
}
