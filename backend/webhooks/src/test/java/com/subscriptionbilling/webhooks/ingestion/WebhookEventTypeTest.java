package com.subscriptionbilling.webhooks.ingestion;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookEventTypeTest {

    @Test
    void resolvesEachRecognizedGatewayTypeValue() {
        assertThat(WebhookEventType.fromGatewayTypeValue("payment_intent.succeeded"))
                .hasValue(WebhookEventType.PAYMENT_SUCCEEDED);
        assertThat(WebhookEventType.fromGatewayTypeValue("payment_intent.payment_failed"))
                .hasValue(WebhookEventType.PAYMENT_FAILED);
        assertThat(WebhookEventType.fromGatewayTypeValue("charge.dispute.created"))
                .hasValue(WebhookEventType.DISPUTE_OPENED);
    }

    @Test
    void anUnrecognizedOrNullTypeValueResolvesToEmpty() {
        assertThat(WebhookEventType.fromGatewayTypeValue("charge.refunded")).isEmpty();
        assertThat(WebhookEventType.fromGatewayTypeValue(null)).isEmpty();
    }
}
