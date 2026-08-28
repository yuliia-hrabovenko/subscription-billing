package com.subscriptionbilling.webhooks.ingestion;

import java.util.Arrays;
import java.util.Optional;

/**
 * The gateway event types this system recognizes. A payload whose {@code type} field
 * doesn't map to one of these is treated as unrecognized, not silently ignored.
 */
public enum WebhookEventType {

    PAYMENT_SUCCEEDED("charge.succeeded"),
    PAYMENT_FAILED("charge.failed"),
    DISPUTE_OPENED("charge.dispute.created");

    private final String gatewayTypeValue;

    WebhookEventType(String gatewayTypeValue) {
        this.gatewayTypeValue = gatewayTypeValue;
    }

    /**
     * Resolves the gateway's raw {@code type} string to a recognized event type.
     *
     * @param gatewayTypeValue the payload's raw {@code type} field value; may be null
     * @return the matching type, or empty if {@code gatewayTypeValue} is null or matches none
     */
    public static Optional<WebhookEventType> fromGatewayTypeValue(String gatewayTypeValue) {
        if (gatewayTypeValue == null) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(candidate -> candidate.gatewayTypeValue.equals(gatewayTypeValue))
                .findFirst();
    }
}
