package com.subscriptionbilling.billingjob.gateway;

import java.math.BigDecimal;

/**
 * The seam the billing job charges a Subscription's card-on-file through, and the
 * webhooks module authenticates inbound gateway events through, so neither depends on a
 * specific gateway's API shape. A business decline and a transient gateway/network
 * failure are both represented in the returned {@link ChargeResult}, not thrown, since
 * the caller must branch on the outcome rather than catch it.
 *
 * <p>PAN safety: no method on this interface accepts or returns a raw card number (PAN).
 * {@link #charge} takes only a gateway-provided payment-method token, never a card
 * number, and every result type carries only gateway references/reasons. No
 * implementation of this interface may log a raw PAN either.
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

    /**
     * Verifies that a webhook request actually originated from the gateway, using the
     * gateway's signature scheme, before the webhooks module trusts, dedupes, or
     * processes the event it carries.
     *
     * @param payload          the raw webhook request body, exactly as received
     * @param signatureHeader  the gateway-supplied signature header sent alongside the payload
     * @return true if the signature is valid for the given payload, false otherwise
     */
    boolean verifyWebhookSignature(String payload, String signatureHeader);
}
