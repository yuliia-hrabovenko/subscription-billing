package com.subscriptionbilling.billingjob.gateway;

/**
 * Outcome of a {@link PaymentGatewayClient#charge} attempt. The three cases drive
 * different handling downstream: {@link Succeeded} lets the billing job advance the
 * Subscription's Anchor Date; {@link Declined} hands off to Dunning without moving it;
 * {@link FailedTransiently} is a gateway- or network-side problem to retry, never treated
 * as a decline.
 */
public sealed interface ChargeResult {

    /** @param gatewayTransactionId the gateway's reference for the completed charge */
    record Succeeded(String gatewayTransactionId) implements ChargeResult {
    }

    /** @param reason the gateway's stated reason the card was declined */
    record Declined(String reason) implements ChargeResult {
    }

    /** @param reason the gateway- or network-side condition that prevented a result (timeout, 5xx, ...) */
    record FailedTransiently(String reason) implements ChargeResult {
    }
}
