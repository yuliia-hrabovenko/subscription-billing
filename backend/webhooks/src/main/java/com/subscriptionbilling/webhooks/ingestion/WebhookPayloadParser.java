package com.subscriptionbilling.webhooks.ingestion;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * Parses a raw webhook request body into a {@link GatewayWebhookEvent}, rejecting
 * anything that isn't well-formed JSON carrying a non-blank {@code id} and a {@code
 * type} this system recognizes.
 */
@Component
public class WebhookPayloadParser {

    private static final String EVENT_ID_FIELD = "id";
    private static final String EVENT_TYPE_FIELD = "type";
    private static final String EVENT_DATA_FIELD = "data";

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * @param rawPayload the raw webhook request body, exactly as received
     * @return the parsed, recognized event
     * @throws MalformedWebhookPayloadException if the body isn't valid JSON, has no
     *                                           non-blank {@code id}, or its {@code
     *                                           type} isn't recognized
     */
    public GatewayWebhookEvent parse(String rawPayload) {
        JsonNode root = readTree(rawPayload);
        String gatewayEventId = root.path(EVENT_ID_FIELD).asText(null);
        if (gatewayEventId == null || gatewayEventId.isBlank()) {
            throw new MalformedWebhookPayloadException("Webhook payload is missing a required non-blank \"id\" field");
        }

        String rawEventType = root.path(EVENT_TYPE_FIELD).asText(null);
        WebhookEventType eventType = WebhookEventType.fromGatewayTypeValue(rawEventType)
                .orElseThrow(() -> new MalformedWebhookPayloadException(
                        "Webhook payload has an unrecognized \"type\": " + rawEventType));

        return new GatewayWebhookEvent(gatewayEventId, eventType, root.path(EVENT_DATA_FIELD));
    }

    private JsonNode readTree(String rawPayload) {
        try {
            JsonNode root = objectMapper.readTree(rawPayload);
            if (root == null || !root.isObject()) {
                throw new MalformedWebhookPayloadException("Webhook payload is not a JSON object");
            }
            return root;
        } catch (JsonProcessingException invalidJson) {
            throw new MalformedWebhookPayloadException("Webhook payload is not valid JSON");
        }
    }
}
