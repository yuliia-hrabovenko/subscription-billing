package com.subscriptionbilling.webhooks.dispatch;

import com.fasterxml.jackson.databind.JsonNode;
import com.subscriptionbilling.webhooks.ingestion.GatewayWebhookEvent;
import com.subscriptionbilling.webhooks.ingestion.MalformedWebhookPayloadException;
import com.subscriptionbilling.webhooks.ingestion.WebhookEventType;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Routes a payment-succeeded/failed event to {@link
 * PaymentOutcomeReconciliationPort#reconcileSucceeded}/{@link
 * PaymentOutcomeReconciliationPort#reconcileFailed}, resolving the Subscription identity
 * from {@code data.object.metadata.subscription_id} (the same shape {@link
 * DisputeWebhookEventDispatcher} resolves from) and the correlation key from {@code
 * data.object.id}. Every other event type falls through to {@code fallback}.
 */
public class PaymentOutcomeWebhookEventDispatcher implements WebhookEventDispatcher {

    private static final String OBJECT_FIELD = "object";
    private static final String ID_FIELD = "id";
    private static final String METADATA_FIELD = "metadata";
    private static final String SUBSCRIPTION_ID_FIELD = "subscription_id";

    private final PaymentOutcomeReconciliationPort reconciliationPort;
    private final WebhookEventDispatcher fallback;
    private final Clock clock;

    public PaymentOutcomeWebhookEventDispatcher(PaymentOutcomeReconciliationPort reconciliationPort,
                                                 WebhookEventDispatcher fallback) {
        this(reconciliationPort, fallback, Clock.systemUTC());
    }

    PaymentOutcomeWebhookEventDispatcher(PaymentOutcomeReconciliationPort reconciliationPort,
                                          WebhookEventDispatcher fallback, Clock clock) {
        this.reconciliationPort = reconciliationPort;
        this.fallback = fallback;
        this.clock = clock;
    }

    @Override
    public void dispatch(GatewayWebhookEvent event) {
        if (event.eventType() != WebhookEventType.PAYMENT_SUCCEEDED && event.eventType() != WebhookEventType.PAYMENT_FAILED) {
            fallback.dispatch(event);
            return;
        }

        UUID subscriptionId = resolveSubscriptionId(event);
        String gatewayReference = resolveGatewayReference(event);
        Instant occurredAt = Instant.now(clock);
        if (event.eventType() == WebhookEventType.PAYMENT_SUCCEEDED) {
            reconciliationPort.reconcileSucceeded(subscriptionId, gatewayReference, occurredAt);
        } else {
            reconciliationPort.reconcileFailed(subscriptionId, gatewayReference, occurredAt);
        }
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
                    "Payment event " + event.gatewayEventId() + " is missing data.object.metadata.subscription_id");
        }
        try {
            return UUID.fromString(rawSubscriptionId);
        } catch (IllegalArgumentException notAUuid) {
            throw new MalformedWebhookPayloadException(
                    "Payment event " + event.gatewayEventId() + " has a non-UUID subscription_id: " + rawSubscriptionId);
        }
    }

    /**
     * @throws MalformedWebhookPayloadException if {@code data.object.id} is missing or blank
     */
    private String resolveGatewayReference(GatewayWebhookEvent event) {
        String gatewayReference = event.data().path(OBJECT_FIELD).path(ID_FIELD).asText(null);
        if (gatewayReference == null || gatewayReference.isBlank()) {
            throw new MalformedWebhookPayloadException(
                    "Payment event " + event.gatewayEventId() + " is missing data.object.id");
        }
        return gatewayReference;
    }
}
