package com.subscriptionbilling.billingjob.gateway;

import java.math.BigDecimal;

/**
 * The seam the billing job charges a Subscription's card-on-file through, so it never
 * depends on a specific gateway's API shape. A business decline and a transient
 * gateway/network failure are both represented in the returned {@link ChargeResult}, not
 * thrown, since the caller must branch on the outcome rather than catch it.
 */
public interface PaymentGatewayClient {

    /**
     * Charges the given amount against the given card-on-file reference.
     *
     * @param paymentMethodToken the Customer's gateway-provided card-on-file reference
     * @param amount             the amount to charge, in the account's single billing currency
     * @return the resolved outcome: success, decline, or transient failure
     */
    ChargeResult charge(String paymentMethodToken, BigDecimal amount);
}
