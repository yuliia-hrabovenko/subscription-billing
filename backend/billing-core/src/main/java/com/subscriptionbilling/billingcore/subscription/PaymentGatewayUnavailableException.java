package com.subscriptionbilling.billingcore.subscription;

/**
 * A self-service payment retry's gateway charge attempt failed transiently (timeout,
 * 5xx, ...) rather than resolving to a business decline. Nothing was recorded and no
 * retry slot was consumed, so this is safe to retry.
 */
public class PaymentGatewayUnavailableException extends RuntimeException {

    public PaymentGatewayUnavailableException(String reason) {
        super("Payment gateway unavailable: " + reason);
    }
}
