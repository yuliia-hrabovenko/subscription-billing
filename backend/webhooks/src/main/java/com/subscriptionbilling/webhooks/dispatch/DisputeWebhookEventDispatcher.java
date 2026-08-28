package com.subscriptionbilling.webhooks.dispatch;

import com.fasterxml.jackson.databind.JsonNode;
import com.subscriptionbilling.webhooks.ingestion.GatewayWebhookEvent;
import com.subscriptionbilling.webhooks.ingestion.MalformedWebhookPayloadException;
import com.subscriptionbilling.webhooks.ingestion.WebhookEventType;

import java.util.UUID;

/**
 * Routes a dispute-opened event to {@link DisputeCancellationPort#cancelForDispute},
 * resolving the Subscription identity from {@code data.object.metadata.subscription_id}.
 * Every other recognized event type falls through to a {@link NoOpWebhookEventDispatcher}
 * — no handler is wired up for payment success/failure yet.
 */
public class DisputeWebhookEventDispatcher implements WebhookEventDispatcher {

    private static final String OBJECT_FIELD = "object";
    private static final String METADATA_FIELD = "metadata";
    private static final String SUBSCRIPTION_ID_FIELD = "subscription_id";

    private final DisputeCancellationPort disputeCancellationPort;
    private final WebhookEventDispatcher fallback = new NoOpWebhookEventDispatcher();

    public DisputeWebhookEventDispatcher(DisputeCancellationPort disputeCancellationPort) {
        this.disputeCancellationPort = disputeCancellationPort;
    }

    @Override
    public void dispatch(GatewayWebhookEvent event) {
        if (event.eventType() != WebhookEventType.DISPUTE_OPENED) {
            fallback.dispatch(event);
            return;
        }
        disputeCancellationPort.cancelForDispute(resolveSubscriptionId(event), event.gatewayEventId());
    }

    /**
     * @throws MalformedWebhookPayloadException if {@code data.object.metadata.subscription_id}
     *         is missing, blank, or not a valid UUID
     */
    private UUID resolveSubscriptionId(GatewayWebhookEvent event) {
        JsonNode data = event.data();
        String rawSubscriptionId = data.path(OBJECT_FIELD).path(METADATA_FIELD).path(SUBSCRIPTION_ID_FIELD).asText(null);
        if (rawSubscriptionId == null || rawSubscriptionId.isBlank()) {
            throw new MalformedWebhookPayloadException(
                    "Dispute event " + event.gatewayEventId() + " is missing data.object.metadata.subscription_id");
        }
        try {
            return UUID.fromString(rawSubscriptionId);
        } catch (IllegalArgumentException notAUuid) {
            throw new MalformedWebhookPayloadException(
                    "Dispute event " + event.gatewayEventId() + " has a non-UUID subscription_id: " + rawSubscriptionId);
        }
    }
}
