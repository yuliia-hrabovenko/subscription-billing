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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Covers {@link DisputeWebhookEventDispatcher}'s routing: a dispute-opened event
 * resolves its Subscription from {@code data.object.metadata.subscription_id} and
 * delegates to {@link DisputeCancellationPort}, every other event type falls through
 * untouched, and a dispute event with no resolvable Subscription id is rejected rather
 * than silently dropped.
 */
@ExtendWith(MockitoExtension.class)
class DisputeWebhookEventDispatcherTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private DisputeCancellationPort disputeCancellationPort;

    private DisputeWebhookEventDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        dispatcher = new DisputeWebhookEventDispatcher(disputeCancellationPort);
    }

    @Test
    void aDisputeOpenedEventWithAResolvableSubscriptionIdDelegatesToTheDisputeCancellationPort() {
        UUID subscriptionId = UUID.randomUUID();

        dispatcher.dispatch(disputeEvent("evt_1", subscriptionId.toString()));

        verify(disputeCancellationPort).cancelForDispute(subscriptionId, "evt_1");
    }

    @Test
    void aNonDisputeEventTypeIsNeverRoutedToTheDisputeCancellationPort() {
        GatewayWebhookEvent event =
                new GatewayWebhookEvent("evt_2", WebhookEventType.PAYMENT_SUCCEEDED, objectMapper.createObjectNode());

        dispatcher.dispatch(event);

        verifyNoInteractions(disputeCancellationPort);
    }

    @Test
    void aDisputeEventMissingTheSubscriptionIdIsRejectedAsMalformed() {
        GatewayWebhookEvent event = disputeEventWithNoMetadata("evt_3");

        assertThatThrownBy(() -> dispatcher.dispatch(event)).isInstanceOf(MalformedWebhookPayloadException.class);

        verifyNoInteractions(disputeCancellationPort);
    }

    @Test
    void aDisputeEventWithANonUuidSubscriptionIdIsRejectedAsMalformed() {
        GatewayWebhookEvent event = disputeEvent("evt_4", "not-a-uuid");

        assertThatThrownBy(() -> dispatcher.dispatch(event)).isInstanceOf(MalformedWebhookPayloadException.class);

        verifyNoInteractions(disputeCancellationPort);
    }

    private GatewayWebhookEvent disputeEvent(String gatewayEventId, String subscriptionId) {
        JsonNode data = readTree("""
                {"object":{"id":"dp_1","metadata":{"subscription_id":"%s"}}}
                """.formatted(subscriptionId));
        return new GatewayWebhookEvent(gatewayEventId, WebhookEventType.DISPUTE_OPENED, data);
    }

    private GatewayWebhookEvent disputeEventWithNoMetadata(String gatewayEventId) {
        JsonNode data = readTree("""
                {"object":{"id":"dp_1"}}
                """);
        return new GatewayWebhookEvent(gatewayEventId, WebhookEventType.DISPUTE_OPENED, data);
    }

    private JsonNode readTree(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
