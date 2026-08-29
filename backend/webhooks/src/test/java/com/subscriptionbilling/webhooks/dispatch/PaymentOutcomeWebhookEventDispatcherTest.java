package com.subscriptionbilling.webhooks.dispatch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.subscriptionbilling.webhooks.ingestion.GatewayWebhookEvent;
import com.subscriptionbilling.webhooks.ingestion.MalformedWebhookPayloadException;
import com.subscriptionbilling.webhooks.ingestion.WebhookEventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Covers {@link PaymentOutcomeWebhookEventDispatcher}'s routing: payment-succeeded/failed
 * events resolve their Subscription and gateway reference from {@code data.object} and
 * delegate to {@link PaymentOutcomeReconciliationPort}, every other event type falls
 * through to the fallback dispatcher untouched, and an event missing either field is
 * rejected rather than silently dropped.
 */
@ExtendWith(MockitoExtension.class)
class PaymentOutcomeWebhookEventDispatcherTest {

    private static final Instant FIXED_NOW = Instant.parse("2027-01-08T10:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private PaymentOutcomeReconciliationPort reconciliationPort;
    @Mock
    private WebhookEventDispatcher fallback;

    private PaymentOutcomeWebhookEventDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        dispatcher = new PaymentOutcomeWebhookEventDispatcher(reconciliationPort, fallback, FIXED_CLOCK);
    }

    @Test
    void aPaymentSucceededEventWithAResolvableSubscriptionIdDelegatesToReconcileSucceeded() {
        UUID subscriptionId = UUID.randomUUID();

        dispatcher.dispatch(paymentEvent(WebhookEventType.PAYMENT_SUCCEEDED, "evt_1", "ch_1", subscriptionId.toString()));

        verify(reconciliationPort).reconcileSucceeded(subscriptionId, "ch_1", FIXED_NOW);
        verifyNoInteractions(fallback);
    }

    @Test
    void aPaymentFailedEventWithAResolvableSubscriptionIdDelegatesToReconcileFailed() {
        UUID subscriptionId = UUID.randomUUID();

        dispatcher.dispatch(paymentEvent(WebhookEventType.PAYMENT_FAILED, "evt_2", "ch_2", subscriptionId.toString()));

        verify(reconciliationPort).reconcileFailed(subscriptionId, "ch_2", FIXED_NOW);
        verifyNoInteractions(fallback);
    }

    @Test
    void aDisputeOpenedEventFallsThroughUntouched() {
        GatewayWebhookEvent event =
                new GatewayWebhookEvent("evt_3", WebhookEventType.DISPUTE_OPENED, objectMapper.createObjectNode());

        dispatcher.dispatch(event);

        verify(fallback).dispatch(event);
        verifyNoInteractions(reconciliationPort);
    }

    @Test
    void aPaymentEventMissingTheSubscriptionIdIsRejectedAsMalformed() {
        GatewayWebhookEvent event = readEvent(WebhookEventType.PAYMENT_SUCCEEDED, "evt_4", """
                {"object":{"id":"ch_1"}}
                """);

        assertThatThrownBy(() -> dispatcher.dispatch(event)).isInstanceOf(MalformedWebhookPayloadException.class);

        verifyNoInteractions(reconciliationPort);
    }

    @Test
    void aPaymentEventWithANonUuidSubscriptionIdIsRejectedAsMalformed() {
        GatewayWebhookEvent event = paymentEvent(WebhookEventType.PAYMENT_SUCCEEDED, "evt_5", "ch_1", "not-a-uuid");

        assertThatThrownBy(() -> dispatcher.dispatch(event)).isInstanceOf(MalformedWebhookPayloadException.class);

        verifyNoInteractions(reconciliationPort);
    }

    @Test
    void aPaymentEventMissingTheGatewayReferenceIsRejectedAsMalformed() {
        UUID subscriptionId = UUID.randomUUID();
        GatewayWebhookEvent event = readEvent(WebhookEventType.PAYMENT_SUCCEEDED, "evt_6", """
                {"object":{"metadata":{"subscription_id":"%s"}}}
                """.formatted(subscriptionId));

        assertThatThrownBy(() -> dispatcher.dispatch(event)).isInstanceOf(MalformedWebhookPayloadException.class);

        verifyNoInteractions(reconciliationPort);
    }

    private GatewayWebhookEvent paymentEvent(WebhookEventType eventType, String gatewayEventId,
                                              String chargeId, String subscriptionId) {
        return readEvent(eventType, gatewayEventId, """
                {"object":{"id":"%s","metadata":{"subscription_id":"%s"}}}
                """.formatted(chargeId, subscriptionId));
    }

    private GatewayWebhookEvent readEvent(WebhookEventType eventType, String gatewayEventId, String dataJson) {
        JsonNode data = readTree(dataJson);
        return new GatewayWebhookEvent(gatewayEventId, eventType, data);
    }

    private JsonNode readTree(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
