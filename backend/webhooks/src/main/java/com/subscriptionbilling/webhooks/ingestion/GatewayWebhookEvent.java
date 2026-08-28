package com.subscriptionbilling.webhooks.ingestion;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A verified, structurally valid gateway webhook event, parsed from the raw request
 * body.
 *
 * @param gatewayEventId the gateway's own event identifier; the dedupe key
 * @param eventType      the recognized event type
 * @param data           the event's type-specific payload, for a handler to interpret
 */
public record GatewayWebhookEvent(String gatewayEventId, WebhookEventType eventType, JsonNode data) {
}
